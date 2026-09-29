package com.psiog.perfinsight.dashboard;

import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.dashboard.DashboardDtos.*;
import com.psiog.perfinsight.jira.JiraUserStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard metrics from users / jira_story / github_activity (+ optional live Jira overlay).
 * Score = weighted, team-relative, explainable; never a single count on its own; lines of code shown, not scored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    public static final String METHOD = "Each metric is compared with the best value in the team for the same period "
            + "(value / team max; for cycle time: team min / value, lower is better), then weighted: "
            + "completed story points 30%, completion rate 20%, cycle time 15%, merged PRs 15%, PR merge rate 10%, commits 10%. "
            + "Metrics with no data are excluded and the remaining weights re-normalised. Lines of code are shown but not scored. "
            + "Bands: >=80 Strong, >=60 On track, else Needs support. Supports - never replaces - qualitative review.";

    private final DashUserRepository users;
    private final JiraStoryRepository stories;
    private final GithubActivityRepository github;
    private final JiraUserStatsService jiraStats;
    private final AppProperties props;

    /** Raw numbers for one developer before scoring. */
    private record Raw(DashUser u, List<JiraStory> st, List<GithubActivity> gh, int total, int done, int inProg,
                       int todo, int points, int donePoints, Double completionRate, Double avgCycle,
                       int prs, int merged, Double mergeRate, int commits, int added, int removed) {}

    // ============================================================================================
    // Overview (Manager / Service Head / Admin)
    // ============================================================================================
    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to, String viewerEmail) {
        DashUser viewer = viewer(viewerEmail);
        if (viewer != null && "EMPLOYEE".equalsIgnoreCase(viewer.getRole()))
            throw new ForbiddenException("Employees can only open their own dashboard: /api/dashboard/developers/" + viewer.getId());

        List<JiraStory> st = storiesInPeriod(from, to);
        List<GithubActivity> gh = githubInPeriod(from, to);
        List<DevMetrics> team = teamMetrics(st, gh);

        int done = count(st, "DONE"), inProg = count(st, "IN_PROGRESS");
        int points = st.stream().mapToInt(s -> nz(s.getStoryPoints())).sum();
        int donePoints = st.stream().filter(s -> isStatus(s, "DONE")).mapToInt(s -> nz(s.getStoryPoints())).sum();
        OptionalDouble cycle = st.stream().filter(s -> cycleDays(s) != null).mapToLong(this::cycleDays).average();

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("developers", team.size());
        kpis.put("totalStories", st.size());
        kpis.put("completedStories", done);
        kpis.put("inProgressStories", inProg);
        kpis.put("toDoStories", st.size() - done - inProg);
        kpis.put("totalStoryPoints", points);
        kpis.put("completedStoryPoints", donePoints);
        kpis.put("completionRatePct", st.isEmpty() ? null : round(100.0 * done / st.size()));
        kpis.put("avgCycleTimeDays", cycle.isPresent() ? round(cycle.getAsDouble()) : null);
        kpis.put("pullRequests", gh.size());
        kpis.put("mergedPullRequests", (int) gh.stream().filter(g -> Boolean.TRUE.equals(g.getMerged())).count());
        kpis.put("commits", gh.stream().mapToInt(g -> nz(g.getCommitsCount())).sum());
        kpis.put("linesAdded", gh.stream().mapToInt(g -> nz(g.getLinesAdded())).sum());
        kpis.put("linesRemoved", gh.stream().mapToInt(g -> nz(g.getLinesRemoved())).sum());
        kpis.put("averageTeamScore", team.stream().filter(m -> m.score() != null)
                .mapToDouble(DevMetrics::score).average().stream().map(DashboardService::round).boxed().findFirst().orElse(null));

        Map<String, Integer> status = new TreeMap<>();
        st.forEach(s -> status.merge(norm(s.getStatus()), 1, Integer::sum));

        List<Map<String, Object>> pointsByDev = team.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", m.name());
            row.put("completedPoints", m.completedStoryPoints());
            row.put("openPoints", m.totalStoryPoints() - m.completedStoryPoints());
            row.put("completedStories", m.completedStories());
            return row;
        }).toList();

        List<Map<String, Object>> ghByDev = team.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", m.name());
            row.put("pullRequests", m.pullRequests());
            row.put("merged", m.mergedPullRequests());
            row.put("commits", m.commits());
            row.put("linesAdded", m.linesAdded());
            row.put("linesRemoved", m.linesRemoved());
            return row;
        }).toList();

        Map<String, Map<String, Object>> repos = new TreeMap<>();
        for (GithubActivity g : gh) {
            Map<String, Object> r = repos.computeIfAbsent(g.getRepositoryName(), k -> new LinkedHashMap<>(
                    Map.of("pullRequests", 0, "merged", 0, "commits", 0, "linesAdded", 0)));
            r.merge("pullRequests", 1, (a, b) -> (Integer) a + (Integer) b);
            r.merge("merged", Boolean.TRUE.equals(g.getMerged()) ? 1 : 0, (a, b) -> (Integer) a + (Integer) b);
            r.merge("commits", nz(g.getCommitsCount()), (a, b) -> (Integer) a + (Integer) b);
            r.merge("linesAdded", nz(g.getLinesAdded()), (a, b) -> (Integer) a + (Integer) b);
        }

        return new Overview(from, to, kpis, status, pointsByDev, ghByDev, repos, weeklyTrend(st, gh),
                team, teamInsights(team, st, gh), METHOD);
    }

    // ============================================================================================
    // Leaderboard / developer list
    // ============================================================================================
    @Transactional(readOnly = true)
    public List<DevMetrics> developers(LocalDate from, LocalDate to) {
        return teamMetrics(storiesInPeriod(from, to), githubInPeriod(from, to));
    }

    // ============================================================================================
    // One developer (Employee sees only self)
    // ============================================================================================
    @Transactional(readOnly = true)
    public DeveloperDetail developer(Long id, LocalDate from, LocalDate to, boolean live, String jiraName,
                                     String projectKey, String viewerEmail) {
        DashUser viewer = viewer(viewerEmail);
        if (viewer != null && "EMPLOYEE".equalsIgnoreCase(viewer.getRole()) && !viewer.getId().equals(id))
            throw new ForbiddenException("Employees can only see their own dashboard");
        DashUser u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));

        List<JiraStory> st = storiesInPeriod(from, to);
        List<GithubActivity> gh = githubInPeriod(from, to);
        List<DevMetrics> team = teamMetrics(st, gh);
        DevMetrics me = team.stream().filter(m -> m.userId().equals(id)).findFirst()
                .orElseGet(() -> scoreAll(List.of(raw(u, st, gh))).get(0));

        List<JiraStory> mySt = st.stream().filter(s -> ownedBy(s.getDeveloper(), id))
                .sorted(Comparator.comparing(JiraStory::getCreatedDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<GithubActivity> myGh = gh.stream().filter(g -> ownedBy(g.getDeveloper(), id))
                .sorted(Comparator.comparing(GithubActivity::getActivityDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        String base = props.getJira().getBaseUrl() == null ? "" : props.getJira().getBaseUrl().replaceAll("/+$", "");
        List<StoryDto> storyDtos = mySt.stream().map(s -> new StoryDto(s.getStoryId(), s.getStoryTitle(), s.getStoryPoints(),
                norm(s.getStatus()), s.getCreatedDate(), s.getCompletedDate(), cycleDays(s),
                base.isEmpty() ? null : base + "/browse/" + s.getStoryId())).toList();
        List<PrDto> prDtos = myGh.stream().map(g -> new PrDto(g.getRepositoryName(), g.getPullRequestId(), g.getCommitsCount(),
                g.getLinesAdded(), g.getLinesRemoved(), Boolean.TRUE.equals(g.getMerged()), g.getActivityDate())).toList();

        Object liveJira = null;
        if (live) {
            String name = jiraName != null && !jiraName.isBlank() ? jiraName
                    : (u.getEmail() != null && u.getEmail().contains("@") ? u.getEmail().substring(0, u.getEmail().indexOf('@')) : u.getName());
            try {
                liveJira = jiraStats.summary(name, null, projectKey, from, to, false, null);
            } catch (RuntimeException e) {
                log.warn("Live Jira lookup failed for {}: {}", name, e.getMessage());
                liveJira = Map.of("error", String.valueOf(e.getMessage()), "triedName", name,
                        "hint", "Pass ?jiraName=<name as in Jira> (e.g. nithish.kumar)");
            }
        }
        return new DeveloperDetail(me, team.size(), storyDtos, prDtos, developerInsights(me, mySt, myGh, team), liveJira, METHOD);
    }

    @Transactional(readOnly = true)
    public List<UserDto> users() {
        return users.findAll().stream().map(UserDto::of).toList();
    }

    @Transactional(readOnly = true)
    public DashUser byEmail(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow(() -> new NotFoundException("User with email", email));
    }

    // ============================================================================================
    // Metrics + scoring
    // ============================================================================================
    private List<DevMetrics> teamMetrics(List<JiraStory> st, List<GithubActivity> gh) {
        Set<Long> withData = new HashSet<>();
        st.forEach(s -> { if (s.getDeveloper() != null) withData.add(s.getDeveloper().getId()); });
        gh.forEach(g -> { if (g.getDeveloper() != null) withData.add(g.getDeveloper().getId()); });
        List<Raw> raws = users.findAll().stream()
                .filter(u -> "EMPLOYEE".equalsIgnoreCase(u.getRole()) || withData.contains(u.getId()))
                .filter(u -> !Boolean.FALSE.equals(u.getActive()))
                .map(u -> raw(u, st, gh))
                .toList();
        return scoreAll(raws);
    }

    private Raw raw(DashUser u, List<JiraStory> allSt, List<GithubActivity> allGh) {
        List<JiraStory> st = allSt.stream().filter(s -> ownedBy(s.getDeveloper(), u.getId())).toList();
        List<GithubActivity> gh = allGh.stream().filter(g -> ownedBy(g.getDeveloper(), u.getId())).toList();
        int done = count(st, "DONE"), inProg = count(st, "IN_PROGRESS");
        int points = st.stream().mapToInt(s -> nz(s.getStoryPoints())).sum();
        int donePoints = st.stream().filter(s -> isStatus(s, "DONE")).mapToInt(s -> nz(s.getStoryPoints())).sum();
        OptionalDouble cyc = st.stream().filter(s -> cycleDays(s) != null).mapToLong(this::cycleDays).average();
        int merged = (int) gh.stream().filter(g -> Boolean.TRUE.equals(g.getMerged())).count();
        return new Raw(u, st, gh, st.size(), done, inProg, st.size() - done - inProg, points, donePoints,
                st.isEmpty() ? null : (double) done / st.size(),
                cyc.isPresent() ? Math.max(0.5, cyc.getAsDouble()) : null,   // same-day completion counts as half a day
                gh.size(), merged, gh.isEmpty() ? null : (double) merged / gh.size(),
                gh.stream().mapToInt(g -> nz(g.getCommitsCount())).sum(),
                gh.stream().mapToInt(g -> nz(g.getLinesAdded())).sum(),
                gh.stream().mapToInt(g -> nz(g.getLinesRemoved())).sum());
    }

    private List<DevMetrics> scoreAll(List<Raw> raws) {
        double maxDonePts = raws.stream().mapToDouble(Raw::donePoints).max().orElse(0);
        double maxRate = raws.stream().filter(r -> r.completionRate() != null).mapToDouble(Raw::completionRate).max().orElse(0);
        double minCycle = raws.stream().filter(r -> r.avgCycle() != null).mapToDouble(Raw::avgCycle).min().orElse(0);
        double maxMerged = raws.stream().mapToDouble(Raw::merged).max().orElse(0);
        double maxMergeRate = raws.stream().filter(r -> r.mergeRate() != null).mapToDouble(Raw::mergeRate).max().orElse(0);
        double maxCommits = raws.stream().mapToDouble(Raw::commits).max().orElse(0);

        List<DevMetrics> out = new ArrayList<>();
        for (Raw r : raws) {
            List<double[]> tmp = new ArrayList<>();          // {normalised or NaN, weight}
            List<String[]> labels = new ArrayList<>();       // {metric, value, note}

            add(tmp, labels, "Completed story points", (double) r.donePoints(), ratio(r.donePoints(), maxDonePts), 0.30,
                    r.total() == 0 ? "No stories in period" : null);
            add(tmp, labels, "Completion rate %", r.completionRate() == null ? null : r.completionRate() * 100,
                    r.completionRate() == null ? null : ratio(r.completionRate(), maxRate), 0.20,
                    r.completionRate() == null ? "No stories in period" : null);
            add(tmp, labels, "Avg cycle time (days, lower is better)", r.avgCycle(),
                    r.avgCycle() == null || minCycle <= 0 ? null : minCycle / r.avgCycle(), 0.15,
                    r.avgCycle() == null ? "No completed stories" : null);
            add(tmp, labels, "Merged pull requests", (double) r.merged(), ratio(r.merged(), maxMerged), 0.15,
                    r.prs() == 0 ? "No pull requests in period" : null);
            add(tmp, labels, "PR merge rate %", r.mergeRate() == null ? null : r.mergeRate() * 100,
                    r.mergeRate() == null ? null : ratio(r.mergeRate(), maxMergeRate), 0.10,
                    r.mergeRate() == null ? "No pull requests in period" : null);
            add(tmp, labels, "Commits", (double) r.commits(), ratio(r.commits(), maxCommits), 0.10,
                    r.prs() == 0 ? "No GitHub activity in period" : null);

            // metrics with no data for this person are excluded -> weights renormalised
            double usedWeight = 0;
            for (int i = 0; i < tmp.size(); i++) if (!Double.isNaN(tmp.get(i)[0]) && labels.get(i)[2] == null) usedWeight += tmp.get(i)[1];

            List<ScorePart> parts = new ArrayList<>();
            double score = 0;
            for (int i = 0; i < tmp.size(); i++) {
                double n = tmp.get(i)[0], w = tmp.get(i)[1];
                boolean included = !Double.isNaN(n) && labels.get(i)[2] == null && usedWeight > 0;
                Double eff = included ? round4(w / usedWeight) : null;
                Double contrib = included ? round(100 * n * w / usedWeight) : null;
                if (included) score += 100 * n * w / usedWeight;
                parts.add(new ScorePart(labels.get(i)[0], labels.get(i)[1] == null ? null : Double.valueOf(labels.get(i)[1]),
                        Double.isNaN(n) ? null : round4(n), w, eff, contrib, included, labels.get(i)[2]));
            }
            parts.add(new ScorePart("Lines added / removed", (double) (r.added() + r.removed()), null, 0, null, null, false,
                    "Shown for context only - lines of code are not a quality signal"));

            boolean enoughData = usedWeight >= 0.5;   // at least half the model has data
            Double finalScore = enoughData ? round(score) : null;
            String band = finalScore == null ? "INSUFFICIENT_DATA" : finalScore >= 80 ? "STRONG" : finalScore >= 60 ? "ON_TRACK" : "NEEDS_SUPPORT";

            out.add(new DevMetrics(r.u().getId(), r.u().getEmployeeId(), r.u().getName(), r.u().getEmail(), r.u().getRole(),
                    r.total(), r.done(), r.inProg(), r.todo(), r.points(), r.donePoints(),
                    r.completionRate() == null ? null : round(r.completionRate() * 100), r.avgCycle() == null ? null : round(r.avgCycle()),
                    r.prs(), r.merged(), r.mergeRate() == null ? null : round(r.mergeRate() * 100),
                    r.commits(), r.added(), r.removed(), finalScore, band, null, parts));
        }
        // rank
        List<DevMetrics> sorted = out.stream()
                .sorted(Comparator.comparing(DevMetrics::score, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        List<DevMetrics> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            DevMetrics m = sorted.get(i);
            ranked.add(new DevMetrics(m.userId(), m.employeeId(), m.name(), m.email(), m.role(), m.totalStories(),
                    m.completedStories(), m.inProgressStories(), m.toDoStories(), m.totalStoryPoints(),
                    m.completedStoryPoints(), m.completionRatePct(), m.avgCycleTimeDays(), m.pullRequests(),
                    m.mergedPullRequests(), m.prMergeRatePct(), m.commits(), m.linesAdded(), m.linesRemoved(),
                    m.score(), m.band(), m.score() == null ? null : i + 1, m.scoreBreakdown()));
        }
        return ranked;
    }

    private static void add(List<double[]> tmp, List<String[]> labels, String metric, Double value, Double norm,
                            double weight, String exclusionNote) {
        tmp.add(new double[]{norm == null ? Double.NaN : Math.min(1.0, norm), weight});
        labels.add(new String[]{metric, value == null ? null : String.valueOf(round(value)), exclusionNote});
    }

    // ============================================================================================
    // Trend + insights
    // ============================================================================================
    private List<WeekPoint> weeklyTrend(List<JiraStory> st, List<GithubActivity> gh) {
        Map<LocalDate, int[]> weeks = new TreeMap<>();   // {stories, points, prsMerged, commits}
        for (JiraStory s : st) {
            if (isStatus(s, "DONE") && s.getCompletedDate() != null) {
                int[] w = weeks.computeIfAbsent(week(s.getCompletedDate()), k -> new int[4]);
                w[0]++;
                w[1] += nz(s.getStoryPoints());
            }
        }
        for (GithubActivity g : gh) {
            if (g.getActivityDate() == null) continue;
            int[] w = weeks.computeIfAbsent(week(g.getActivityDate()), k -> new int[4]);
            if (Boolean.TRUE.equals(g.getMerged())) w[2]++;
            w[3] += nz(g.getCommitsCount());
        }
        return weeks.entrySet().stream()
                .map(e -> new WeekPoint(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .toList();
    }

    private List<String> teamInsights(List<DevMetrics> team, List<JiraStory> st, List<GithubActivity> gh) {
        List<String> out = new ArrayList<>();
        team.stream().filter(m -> m.rank() != null && m.rank() == 1).findFirst().ifPresent(m ->
                out.add(m.name() + " leads the period with " + m.completedStoryPoints() + " completed story points and "
                        + m.mergedPullRequests() + " merged PRs."));
        long stale = st.stream().filter(s -> isStatus(s, "IN_PROGRESS") && s.getCreatedDate() != null
                && ChronoUnit.DAYS.between(s.getCreatedDate(), LocalDate.now()) > 7).count();
        if (stale > 0) out.add(stale + " story(ies) have been in progress for more than 7 days - check for blockers.");
        long unmerged = gh.stream().filter(g -> !Boolean.TRUE.equals(g.getMerged())).count();
        if (unmerged > 0) out.add(unmerged + " pull request(s) are not merged yet.");
        long big = gh.stream().filter(g -> nz(g.getLinesAdded()) + nz(g.getLinesRemoved()) > 1000).count();
        if (big > 0) out.add(big + " PR(s) changed more than 1000 lines - consider smaller PRs for easier review.");
        long unassigned = st.stream().filter(s -> s.getDeveloper() == null).count();
        if (unassigned > 0) out.add(unassigned + " story(ies) have no developer assigned.");
        team.stream().filter(m -> m.totalStories() > 0 && m.pullRequests() == 0).forEach(m ->
                out.add(m.name() + " has Jira stories but no GitHub activity - check Git identity mapping."));
        return out;
    }

    private List<String> developerInsights(DevMetrics me, List<JiraStory> st, List<GithubActivity> gh, List<DevMetrics> team) {
        List<String> out = new ArrayList<>();
        if (me.score() != null)
            out.add("Score " + me.score() + " (" + me.band().replace('_', ' ').toLowerCase() + "), rank " + me.rank() + " of " + team.size() + ".");
        else out.add("Not enough data in this period for a score - use qualitative review.");
        me.scoreBreakdown().stream().filter(ScorePart::included)
                .max(Comparator.comparing(ScorePart::normalised)).ifPresent(p -> out.add("Strongest area: " + p.metric() + "."));
        me.scoreBreakdown().stream().filter(ScorePart::included)
                .min(Comparator.comparing(ScorePart::normalised)).ifPresent(p -> out.add("Area to discuss: " + p.metric() + "."));
        st.stream().filter(s -> isStatus(s, "IN_PROGRESS") && s.getCreatedDate() != null
                        && ChronoUnit.DAYS.between(s.getCreatedDate(), LocalDate.now()) > 7)
                .forEach(s -> out.add(s.getStoryId() + " has been in progress since " + s.getCreatedDate() + "."));
        gh.stream().filter(g -> !Boolean.TRUE.equals(g.getMerged()))
                .forEach(g -> out.add(g.getPullRequestId() + " in " + g.getRepositoryName() + " is not merged yet."));
        OptionalDouble teamCycle = team.stream().filter(m -> m.avgCycleTimeDays() != null).mapToDouble(DevMetrics::avgCycleTimeDays).average();
        if (me.avgCycleTimeDays() != null && teamCycle.isPresent() && me.avgCycleTimeDays() > 1.5 * teamCycle.getAsDouble())
            out.add("Cycle time " + me.avgCycleTimeDays() + " days vs team average " + round(teamCycle.getAsDouble()) + ".");
        return out;
    }

    // ============================================================================================
    // helpers
    // ============================================================================================
    /** Story counts in the period if it was open at any time in it. */
    private List<JiraStory> storiesInPeriod(LocalDate from, LocalDate to) {
        return stories.findAll().stream()
                .filter(s -> to == null || s.getCreatedDate() == null || !s.getCreatedDate().isAfter(to))
                .filter(s -> from == null || s.getCompletedDate() == null || !s.getCompletedDate().isBefore(from))
                .toList();
    }

    private List<GithubActivity> githubInPeriod(LocalDate from, LocalDate to) {
        return github.findAll().stream()
                .filter(g -> g.getActivityDate() == null
                        || ((from == null || !g.getActivityDate().isBefore(from)) && (to == null || !g.getActivityDate().isAfter(to))))
                .toList();
    }

    private DashUser viewer(String email) {
        return email == null || email.isBlank() ? null : users.findByEmailIgnoreCase(email).orElse(null);
    }

    private Long cycleDays(JiraStory s) {
        return isStatus(s, "DONE") && s.getCreatedDate() != null && s.getCompletedDate() != null
                ? ChronoUnit.DAYS.between(s.getCreatedDate(), s.getCompletedDate()) : null;
    }

    private static boolean ownedBy(DashUser d, Long id) {
        return d != null && d.getId().equals(id);
    }

    private static int count(List<JiraStory> st, String status) {
        return (int) st.stream().filter(s -> isStatus(s, status)).count();
    }

    private static boolean isStatus(JiraStory s, String status) {
        return status.equals(norm(s.getStatus()));
    }

    /** "In Progress", "in_progress", "IN-PROGRESS" -> IN_PROGRESS ; "To Do"/"TODO" -> TO_DO */
    static String norm(String status) {
        if (status == null) return "TO_DO";
        String s = status.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return switch (s) {
            case "TODO", "OPEN", "BACKLOG", "TO_DO" -> "TO_DO";
            case "IN_PROGRESS", "IN_REVIEW", "INPROGRESS" -> "IN_PROGRESS";
            case "DONE", "CLOSED", "RESOLVED", "COMPLETED" -> "DONE";
            default -> s;
        };
    }

    private static LocalDate week(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static Double ratio(double v, double max) {
        return max <= 0 ? null : v / max;
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static double round(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }
}