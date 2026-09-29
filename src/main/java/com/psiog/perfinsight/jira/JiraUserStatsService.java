package com.psiog.perfinsight.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.psiog.perfinsight.common.BadRequestException;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.jira.JiraDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * name (e.g. "nithish.kumar") -> Jira user -> in project KEY:
 *  tickets assigned, story points, time HE logged (worklogs), commits and PRs from the Development panel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraUserStatsService {

    private final JiraApiClient jira;
    private final AppProperties props;
    private final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();

    private volatile List<String> detectedStoryPointFields;

    // ------------------------------------------------------------------------------------------------
    // 1. name -> user
    // ------------------------------------------------------------------------------------------------

    public List<JiraUser> findUsers(String name, String projectKey) {
        String q = name.trim();
        List<JsonNode> raw = new ArrayList<>();
        JsonNode res = projectKey == null ? jira.searchUsers(q) : jira.assignableUsers(projectKey, q, null);
        res.forEach(raw::add);
        if (raw.isEmpty() && (q.contains(".") || q.contains("_"))) {
            // "nithish.kumar" -> "nithish kumar" (display-name search)
            JsonNode res2 = projectKey == null ? jira.searchUsers(q.replaceAll("[._]", " "))
                    : jira.assignableUsers(projectKey, q.replaceAll("[._]", " "), null);
            res2.forEach(raw::add);
        }
        if (raw.isEmpty() && projectKey != null) jira.searchUsers(q).forEach(raw::add); // exists, but maybe not in project
        return raw.stream()
                .filter(u -> !"app".equals(JiraApiClient.textOrNull(u, "accountType")))
                .map(u -> toUser(u, score(q, u)))
                .collect(Collectors.toMap(JiraUser::accountId, x -> x, (a, b) -> a, LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(JiraUser::matchScore).reversed())
                .toList();
    }

    JiraUser resolve(String name, String accountId, String projectKey) {
        if (accountId != null && !accountId.isBlank()) {
            return findUsers(name == null ? accountId : name, projectKey).stream()
                    .filter(u -> u.accountId().equals(accountId)).findFirst()
                    .orElse(new JiraUser(accountId, name, null, true, "atlassian", 1.0));
        }
        List<JiraUser> candidates = findUsers(name, projectKey);
        if (candidates.isEmpty()) throw new BadRequestException("No Jira user matches '" + name + "'");
        JiraUser best = candidates.get(0);
        boolean ambiguous = candidates.size() > 1 && candidates.get(1).matchScore() >= best.matchScore() - 0.02
                && best.matchScore() < 1.0;
        if (ambiguous || best.matchScore() < 0.80) {
            String list = candidates.stream().limit(5)
                    .map(u -> u.displayName() + " [accountId=" + u.accountId() + "]").collect(Collectors.joining(", "));
            throw new BadRequestException("'" + name + "' is ambiguous - pass ?accountId=... Candidates: " + list);
        }
        return best;
    }

    /** 1.0 exact email local part; 0.98 exact display name; else Jaro-Winkler on normalised names. */
    double score(String input, JsonNode u) {
        String in = norm(input);
        String email = Optional.ofNullable(JiraApiClient.textOrNull(u, "emailAddress")).orElse("").toLowerCase(Locale.ROOT);
        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : "";
        if (!local.isEmpty() && (local.equals(input.toLowerCase(Locale.ROOT)) || email.equals(input.toLowerCase(Locale.ROOT)))) return 1.0;
        String dn = norm(Optional.ofNullable(JiraApiClient.textOrNull(u, "displayName")).orElse(""));
        if (dn.equals(in)) return 0.98;
        double s = jw.apply(in, dn);
        if (!local.isEmpty()) s = Math.max(s, jw.apply(in, norm(local)));
        return Math.round(s * 1000) / 1000.0;
    }

    // ------------------------------------------------------------------------------------------------
    // 2. summary
    // ------------------------------------------------------------------------------------------------

    public UserSummary summary(String name, String accountId, String projectKeyParam, LocalDate from, LocalDate to,
                               Boolean includeDevParam, List<String> gitAliases) {
        String projectKey = blank(projectKeyParam) ? props.getJira().getDefaultProjectKey() : projectKeyParam;
        if (blank(projectKey)) throw new BadRequestException("projectKey is required (or set JIRA_PROJECT_KEY)");
        if (from != null && to != null && to.isBefore(from)) throw new BadRequestException("to must be on/after from");
        List<String> warnings = new ArrayList<>();

        JsonNode p = jira.project(projectKey);
        JiraUser user = resolve(name, accountId, projectKey);
        boolean assignable = jira.assignableUsers(projectKey, null, user.accountId()).size() > 0;
        if (!assignable) warnings.add(user.displayName() + " is not an assignable user in " + projectKey
                + " (may have left the project, or lacks the Assignable permission)");
        ProjectInfo project = new ProjectInfo(JiraApiClient.textOrNull(p, "key"), JiraApiClient.textOrNull(p, "name"),
                JiraApiClient.textOrNull(p, "id"), assignable);

        List<String> spFields = storyPointFields();
        if (spFields.isEmpty()) warnings.add("No story points field found - set JIRA_STORY_POINTS_FIELDS (e.g. customfield_10016)");

        // ---- tickets assigned to him ----------------------------------------------------------------
        String period = periodClause(from, to);
        String jql = "project = \"" + projectKey + "\" AND assignee = \"" + user.accountId() + "\"" + period + " ORDER BY updated DESC";
        List<String> fields = new ArrayList<>(List.of("summary", "status", "issuetype", "priority", "created", "updated",
                "resolutiondate", "timeoriginalestimate", "timespent"));
        fields.addAll(spFields);
        int max = props.getJira().getMaxIssues();
        List<JsonNode> assigned = jira.searchIssues(jql, fields, max);
        if (assigned.size() >= max) warnings.add("Stopped at " + max + " issues (app.jira.max-issues)");

        // ---- time HE logged (on any issue in the project, not only his own) -------------------------
        String wlJql = "project = \"" + projectKey + "\" AND worklogAuthor = \"" + user.accountId() + "\""
                + (from != null ? " AND worklogDate >= \"" + from + "\"" : "")
                + (to != null ? " AND worklogDate <= \"" + to + "\"" : "");
        List<JsonNode> worked = jira.searchIssues(wlJql, List.of("summary"), max);
        Long afterMs = from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        Long beforeMs = to == null ? null : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1;
        long seconds = 0;
        int worklogCount = 0;
        Map<String, Double> hoursByIssue = new LinkedHashMap<>();
        for (JsonNode issue : worked) {
            String key = issue.path("key").asText();
            long s = 0;
            for (JsonNode wl : jira.worklogs(key, afterMs, beforeMs)) {
                if (!user.accountId().equals(wl.path("author").path("accountId").asText())) continue;
                Instant started = JsonMaps.toInstant(JiraApiClient.textOrNull(wl, "started"));
                if (started != null && afterMs != null && started.toEpochMilli() < afterMs) continue;
                if (started != null && beforeMs != null && started.toEpochMilli() > beforeMs) continue;
                s += wl.path("timeSpentSeconds").asLong(0);
                worklogCount++;
            }
            if (s > 0) hoursByIssue.put(key, hours(s));
            seconds += s;
        }

        // ---- tickets + story points -------------------------------------------------------------------
        int done = 0, inProgress = 0, todo = 0, noEstimate = 0;
        double spTotal = 0, spDone = 0, spProgress = 0, spTodo = 0;
        long originalEstimate = 0;
        Map<String, Integer> byStatus = new TreeMap<>(), byType = new TreeMap<>();
        List<IssueRow> rows = new ArrayList<>();
        Map<String, int[]> devByIssue = new HashMap<>();

        Development dev = Boolean.FALSE.equals(includeDevParam) || !props.getJira().isIncludeDevInfo()
                ? new Development(false, 0, 0, 0, 0, 0, 0, 0, List.of(), "Development info not requested")
                : development(assigned, user, name, gitAliases, devByIssue, warnings);

        for (JsonNode issue : assigned) {
            JsonNode f = issue.path("fields");
            String cat = f.path("status").path("statusCategory").path("key").asText("");
            Double sp = storyPoints(f, spFields);
            double spv = sp == null ? 0 : sp;
            if (sp == null) noEstimate++;
            switch (cat) {
                case "done" -> { done++; spDone += spv; }
                case "indeterminate" -> { inProgress++; spProgress += spv; }
                default -> { todo++; spTodo += spv; }
            }
            spTotal += spv;
            originalEstimate += f.path("timeoriginalestimate").asLong(0);
            byStatus.merge(f.path("status").path("name").asText("Unknown"), 1, Integer::sum);
            byType.merge(f.path("issuetype").path("name").asText("Unknown"), 1, Integer::sum);
            String key = issue.path("key").asText();
            int[] d = devByIssue.getOrDefault(issue.path("id").asText(), new int[]{0, 0});
            rows.add(new IssueRow(key, issue.path("id").asText(), f.path("summary").asText(null),
                    f.path("issuetype").path("name").asText(null), f.path("status").path("name").asText(null), cat,
                    f.path("priority").path("name").asText(null), sp, hoursByIssue.get(key),
                    JiraApiClient.textOrNull(f, "created"), JiraApiClient.textOrNull(f, "resolutiondate"),
                    d[0], d[1], jira.browseUrl(key)));
        }

        Tickets tickets = new Tickets(assigned.size(), done, inProgress, todo, byStatus, byType);
        StoryPoints points = new StoryPoints(round(spTotal), round(spDone), round(spProgress), round(spTodo), noEstimate, spFields);
        TimeLogged time = new TimeLogged(seconds, hours(seconds), pretty(seconds), worklogCount, hoursByIssue.size(),
                originalEstimate, hoursByIssue);
        return new UserSummary(user, project, from, to, jql, tickets, points, time, dev, rows, warnings);
    }

    // ------------------------------------------------------------------------------------------------
    // 3. commits / PRs via the Development panel
    // ------------------------------------------------------------------------------------------------

    private Development development(List<JsonNode> issues, JiraUser user, String name, List<String> gitAliases,
                                    Map<String, int[]> devByIssue, List<String> warnings) {
        Set<String> me = new HashSet<>();
        if (user.displayName() != null) me.add(norm(user.displayName()));
        if (name != null) { me.add(norm(name)); me.add(name.toLowerCase(Locale.ROOT)); }
        if (user.email() != null) {
            String e = user.email().toLowerCase(Locale.ROOT);
            me.add(e);
            if (e.contains("@")) me.add(e.substring(0, e.indexOf('@')));
        }
        if (gitAliases != null) gitAliases.stream().filter(a -> !a.isBlank()).forEach(a -> { me.add(a.toLowerCase(Locale.ROOT).trim()); me.add(norm(a)); });

        int commitsLinked = 0, commitsMine = 0, prLinked = 0, prMine = 0, merged = 0, open = 0, withDev = 0, lookups = 0;
        Set<String> sources = new TreeSet<>();
        int limit = props.getJira().getMaxDevLookups();
        try {
            for (JsonNode issue : issues) {
                if (lookups++ >= limit) { warnings.add("Development info read for the first " + limit + " issues only"); break; }
                String id = issue.path("id").asText();
                JsonNode sum = jira.devSummary(id).path("summary");
                int c = sum.path("repository").path("overall").path("count").asInt(0);
                int pr = sum.path("pullrequest").path("overall").path("count").asInt(0);
                if (c == 0 && pr == 0) continue;
                withDev++;
                commitsLinked += c;
                prLinked += pr;
                devByIssue.put(id, new int[]{c, pr});

                Set<String> appTypes = new LinkedHashSet<>();
                sum.path("repository").path("byInstanceType").fieldNames().forEachRemaining(appTypes::add);
                sum.path("pullrequest").path("byInstanceType").fieldNames().forEachRemaining(appTypes::add);
                sources.addAll(appTypes);
                for (String app : appTypes) {
                    if (c > 0) {
                        for (JsonNode d : jira.devDetail(id, app, "repository").path("detail"))
                            for (JsonNode repo : d.path("repositories"))
                                for (JsonNode commit : repo.path("commits"))
                                    if (isMe(commit.path("author"), me)) commitsMine++;
                    }
                    if (pr > 0) {
                        for (JsonNode d : jira.devDetail(id, app, "pullrequest").path("detail"))
                            for (JsonNode p : d.path("pullRequests")) {
                                String status = p.path("status").asText("");
                                if (isMe(p.path("author"), me)) {
                                    prMine++;
                                    if ("MERGED".equalsIgnoreCase(status)) merged++;
                                    if ("OPEN".equalsIgnoreCase(status)) open++;
                                }
                            }
                    }
                }
            }
        } catch (JiraApiException e) {
            log.warn("Development panel lookup failed: {}", e.getMessage());
            return new Development(false, commitsLinked, commitsMine, prLinked, prMine, merged, open, withDev,
                    List.copyOf(sources), "Could not read the Development panel (" + e.getMessage()
                    + "). It needs the GitHub/Bitbucket/GitLab for Jira app and View Development Tools permission.");
        }
        String note = withDev == 0
                ? "No commits/PRs linked to these issues. Commits and PRs appear only when the Git tool is connected to Jira "
                  + "(e.g. 'GitHub for Jira') and commit messages / branches / PR titles contain the issue key."
                : "Linked = all commits/PRs referencing his issues. AuthoredByHim = author name matched his Jira name, "
                  + "email or the gitAliases you passed (add ?gitAliases=<github-login> if his Git name differs).";
        return new Development(true, commitsLinked, commitsMine, prLinked, prMine, merged, open, withDev, List.copyOf(sources), note);
    }

    private boolean isMe(JsonNode author, Set<String> me) {
        for (String f : new String[]{"name", "email", "login", "displayName"}) {
            String v = JiraApiClient.textOrNull(author, f);
            if (v != null && (me.contains(v.toLowerCase(Locale.ROOT).trim()) || me.contains(norm(v)))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------

    public List<String> storyPointFields() {
        String cfg = props.getJira().getStoryPointsFields();
        if (!blank(cfg)) return Arrays.stream(cfg.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (detectedStoryPointFields == null) {
            List<String> found = new ArrayList<>();
            for (JsonNode f : jira.fields()) {
                String n = f.path("name").asText("").toLowerCase(Locale.ROOT);
                String custom = f.path("schema").path("custom").asText("");
                if (n.equals("story points") || n.equals("story point estimate") || custom.endsWith("jsw-story-points"))
                    found.add(f.path("id").asText());
            }
            detectedStoryPointFields = List.copyOf(new LinkedHashSet<>(found));
            log.info("Detected Jira story points fields: {}", detectedStoryPointFields);
        }
        return detectedStoryPointFields;
    }

    private static Double storyPoints(JsonNode fields, List<String> spFields) {
        for (String f : spFields) {
            JsonNode v = fields.get(f);
            if (v != null && v.isNumber()) return v.asDouble();
        }
        return null;
    }

    private static String periodClause(LocalDate from, LocalDate to) {
        // tickets that were active in the period: touched after 'from' and created before 'to'
        return (from != null ? " AND updated >= \"" + from + "\"" : "")
                + (to != null ? " AND created <= \"" + to + " 23:59\"" : "");
    }

    private JiraUser toUser(JsonNode u, double score) {
        return new JiraUser(JiraApiClient.textOrNull(u, "accountId"), JiraApiClient.textOrNull(u, "displayName"),
                JiraApiClient.textOrNull(u, "emailAddress"), u.path("active").asBoolean(true),
                JiraApiClient.textOrNull(u, "accountType"), score);
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("@.*$", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    static String pretty(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h + "h " + m + "m";
    }

    private static double hours(long seconds) {
        return Math.round(seconds / 36.0) / 100.0;
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
