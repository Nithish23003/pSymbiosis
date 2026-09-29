package com.psiog.perfinsight.insight;

import com.psiog.perfinsight.activity.*;
import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.org.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Criterion 9: rule-based detection of unusual patterns / likely metric gaming. Deterministic and explainable;
 * the LLM only turns findings into plain English. Flags are prompts for a conversation, not penalties.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyService {

    private final ActivityRepository activities;
    private final EffectiveActivityService effective;
    private final AnomalyFlagRepository flags;
    private final AssociateRepository associates;
    private final ProjectRepository projects;
    private final AuditService audit;

    private record Finding(String rule, String severity, String title, String description,
                           Map<String, Object> metrics, List<EffectiveActivity> evidence) {}

    @Transactional
    public List<AnomalyFlag> detect(LocalDate from, LocalDate to) {
        List<EffectiveActivity> all = effective.effective(activities.findAttributableBetween(from, to))
                .stream().filter(a -> !a.excluded()).toList();
        Map<Long, List<EffectiveActivity>> byPerson = all.stream().collect(Collectors.groupingBy(EffectiveActivity::associateId));

        // persona per person = most common persona on their attributed activity
        Map<Long, Persona> persona = new HashMap<>();
        byPerson.forEach((id, acts) -> acts.stream().map(EffectiveActivity::persona).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> persona.put(id, e.getKey())));

        // cohort medians
        Map<Persona, Double> medianPrs = medianBy(byPerson, persona, acts -> (double) prs(acts).size());
        Map<Persona, Double> medianPts = medianBy(byPerson, persona, acts -> {
            List<EffectiveActivity> done = done(acts);
            return done.isEmpty() ? null : done.stream().mapToDouble(AnomalyService::points).average().orElse(0);
        });
        Set<String> prText = all.stream().filter(a -> a.type() == ActivityType.PULL_REQUEST)
                .map(a -> ((a.title() == null ? "" : a.title()) + " " + Objects.toString(a.attr("branch"), "")).toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<AnomalyFlag> out = new ArrayList<>();
        for (var e : byPerson.entrySet()) {
            Long who = e.getKey();
            List<EffectiveActivity> acts = e.getValue();
            Persona p = persona.get(who);
            List<Finding> found = new ArrayList<>();
            selfMerged(acts).ifPresent(found::add);
            rubberStamp(acts).ifPresent(found::add);
            prSplitting(acts, medianPrs.get(p)).ifPresent(found::add);
            if (p != Persona.LEAD) ticketsWithoutCode(acts, prText).ifPresent(found::add);
            endOfPeriodSpike(acts, to).ifPresent(found::add);
            pointInflation(acts, medianPts.get(p)).ifPresent(found::add);
            manualHeavy(acts).ifPresent(found::add);
            singleDayBurst(acts).ifPresent(found::add);
            for (Finding f : found) out.add(save(who, f, from, to));
        }
        log.info("Anomaly detection {}..{}: {} flags", from, to, out.size());
        return out;
    }

    @Transactional
    public AnomalyFlag review(Long id, String status, String note) {
        AnomalyFlag f = flags.findById(id).orElseThrow(() -> new NotFoundException("AnomalyFlag", id));
        if (!Set.of("OPEN", "DISMISSED", "CONFIRMED").contains(status)) throw new IllegalArgumentException("status must be OPEN, DISMISSED or CONFIRMED");
        f.setStatus(status);
        f.setReviewNote(note);
        f.setReviewedBy(audit.currentActor());
        f.setReviewedAt(Instant.now());
        audit.log("ANOMALY_REVIEWED", "AnomalyFlag", id, Map.of("status", status, "note", note == null ? "" : note));
        return f;
    }

    // ---- rules ----------------------------------------------------------------------------------------

    private Optional<Finding> selfMerged(List<EffectiveActivity> acts) {
        List<EffectiveActivity> merged = prs(acts).stream().filter(a -> JsonMaps.bool(a.attributes(), "merged")).toList();
        List<EffectiveActivity> self = merged.stream().filter(a -> JsonMaps.bool(a.attributes(), "selfMerged")).toList();
        if (self.size() >= 3 && self.size() >= 0.5 * merged.size())
            return Optional.of(new Finding("SELF_MERGED_PRS", "MEDIUM", "Most PRs merged by their own author",
                    self.size() + " of " + merged.size() + " merged PRs were merged by the author, which can bypass review. "
                            + "Check branch protection / whether this was agreed (e.g. hotfixes).",
                    Map.of("selfMerged", self.size(), "merged", merged.size()), self));
        return Optional.empty();
    }

    private Optional<Finding> rubberStamp(List<EffectiveActivity> acts) {
        List<EffectiveActivity> approvals = acts.stream()
                .filter(a -> a.type() == ActivityType.CODE_REVIEW && JsonMaps.bool(a.attributes(), "approved")).toList();
        List<EffectiveActivity> quick = approvals.stream().filter(a -> {
            Double mins = JsonMaps.num(a.attributes(), "minutesAfterPrOpened");
            Double lines = JsonMaps.num(a.attributes(), "prLinesChanged");
            Double body = JsonMaps.num(a.attributes(), "bodyLength");
            return mins != null && mins < 10 && lines != null && lines >= 300 && (body == null || body < 20);
        }).toList();
        if (quick.size() >= 3 && quick.size() >= 0.5 * approvals.size())
            return Optional.of(new Finding("RUBBER_STAMP_REVIEWS", "MEDIUM", "Very fast approvals on large PRs",
                    quick.size() + " of " + approvals.size() + " approvals were given within 10 minutes on PRs of 300+ changed lines "
                            + "with no review comment. Review counts may be inflated.",
                    Map.of("quickApprovals", quick.size(), "approvals", approvals.size()), quick));
        return Optional.empty();
    }

    private Optional<Finding> prSplitting(List<EffectiveActivity> acts, Double cohortMedian) {
        List<EffectiveActivity> prs = prs(acts);
        if (prs.size() < 8 || cohortMedian == null || cohortMedian <= 0 || prs.size() < 2 * cohortMedian) return Optional.empty();
        double[] lines = prs.stream().map(a -> JsonMaps.num(a.attributes(), "linesChanged")).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).sorted().toArray();
        if (lines.length == 0) return Optional.empty();
        double median = lines[lines.length / 2];
        if (median >= 15) return Optional.empty();
        return Optional.of(new Finding("PR_SPLITTING", "MEDIUM", "Unusually many, very small PRs",
                prs.size() + " PRs (peer median " + Math.round(cohortMedian) + ") with a median of " + Math.round(median)
                        + " changed lines. Could be healthy small batches - or splitting work to raise PR counts.",
                Map.of("prs", prs.size(), "peerMedianPrs", cohortMedian, "medianLines", median), prs));
    }

    private Optional<Finding> ticketsWithoutCode(List<EffectiveActivity> acts, Set<String> prText) {
        boolean usesGit = acts.stream().anyMatch(a -> a.type() == ActivityType.PULL_REQUEST || a.type() == ActivityType.CODE_REVIEW);
        List<EffectiveActivity> done = done(acts);
        if (!usesGit || done.size() < 5) return Optional.empty();
        List<EffectiveActivity> unlinked = done.stream().filter(t -> {
            String key = t.externalId().toUpperCase(Locale.ROOT);
            return prText.stream().noneMatch(txt -> txt.contains(key));
        }).toList();
        double share = (double) unlinked.size() / done.size();
        if (share < 0.8) return Optional.empty();
        return Optional.of(new Finding("TICKETS_WITHOUT_CODE", "LOW", "Completed tickets rarely linked to code",
                unlinked.size() + " of " + done.size() + " completed tickets have no PR mentioning the ticket key. "
                        + "May be non-code work or missing links - or tickets closed without delivery.",
                Map.of("unlinked", unlinked.size(), "completed", done.size()), unlinked));
    }

    private Optional<Finding> endOfPeriodSpike(List<EffectiveActivity> acts, LocalDate periodEnd) {
        List<EffectiveActivity> done = done(acts);
        if (done.size() < 6) return Optional.empty();
        LocalDate cutoff = periodEnd.minusDays(2);
        List<EffectiveActivity> late = done.stream().filter(a -> !a.occurredOn().isBefore(cutoff)).toList();
        if (late.size() < 0.4 * done.size()) return Optional.empty();
        return Optional.of(new Finding("END_OF_PERIOD_SPIKE", "LOW", "Many tickets closed in the last days of the period",
                late.size() + " of " + done.size() + " completed tickets were closed in the final 3 days of the period.",
                Map.of("lateClosures", late.size(), "completed", done.size()), late));
    }

    private Optional<Finding> pointInflation(List<EffectiveActivity> acts, Double cohortMedian) {
        List<EffectiveActivity> done = done(acts);
        if (done.size() < 4 || cohortMedian == null || cohortMedian <= 0) return Optional.empty();
        double avg = done.stream().mapToDouble(AnomalyService::points).average().orElse(0);
        if (avg < 2 * cohortMedian) return Optional.empty();
        return Optional.of(new Finding("STORY_POINT_INFLATION", "MEDIUM", "Estimates well above peers",
                String.format("Average %.1f points per completed ticket vs a peer median of %.1f. Check estimation consistency.", avg, cohortMedian),
                Map.of("avgPoints", avg, "peerMedian", cohortMedian), done));
    }

    private Optional<Finding> manualHeavy(List<EffectiveActivity> acts) {
        List<EffectiveActivity> manual = acts.stream().filter(a -> a.origin() == ActivityOrigin.MANUAL).toList();
        if (acts.size() < 5 || manual.size() < 0.5 * acts.size()) return Optional.empty();
        return Optional.of(new Finding("MANUAL_DATA_HEAVY", "LOW", "Mostly manually entered data",
                manual.size() + " of " + acts.size() + " activities were entered manually. Fine for non-integrated tools - worth a quick check.",
                Map.of("manual", manual.size(), "total", acts.size()), manual));
    }

    private Optional<Finding> singleDayBurst(List<EffectiveActivity> acts) {
        if (acts.size() < 10) return Optional.empty();
        var byDay = acts.stream().collect(Collectors.groupingBy(EffectiveActivity::occurredOn));
        var max = byDay.entrySet().stream().max(Comparator.comparingInt(en -> en.getValue().size())).orElseThrow();
        if (max.getValue().size() < 0.4 * acts.size()) return Optional.empty();
        return Optional.of(new Finding("SINGLE_DAY_BURST", "LOW", "Large share of activity on a single day",
                max.getValue().size() + " of " + acts.size() + " activities happened on " + max.getKey()
                        + ". Could be a bulk import, a release day - or bulk status updates.",
                Map.of("day", max.getKey().toString(), "count", max.getValue().size(), "total", acts.size()), max.getValue()));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private AnomalyFlag save(Long associateId, Finding f, LocalDate from, LocalDate to) {
        AnomalyFlag flag = flags.findByAssociateIdAndRuleKeyAndPeriodStartAndPeriodEnd(associateId, f.rule(), from, to)
                .orElseGet(AnomalyFlag::new);
        if (flag.getId() == null) {
            flag.setAssociate(associates.getReferenceById(associateId));
            flag.setRuleKey(f.rule());
            flag.setPeriodStart(from);
            flag.setPeriodEnd(to);
            flag.setStatus("OPEN");
        }
        Long projectId = f.evidence().stream().map(EffectiveActivity::projectId).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        flag.setProject(projectId == null ? null : projects.getReferenceById(projectId));
        flag.setSeverity(f.severity());
        flag.setTitle(f.title());
        flag.setDescription(f.description());
        flag.setMetrics(new HashMap<>(f.metrics()));
        flag.setEvidenceActivityIds(f.evidence().stream().map(EffectiveActivity::id).limit(200).collect(Collectors.toList()));
        flag.setDetectedAt(Instant.now());
        return flags.save(flag);
    }

    private static List<EffectiveActivity> prs(List<EffectiveActivity> acts) {
        return acts.stream().filter(a -> a.type() == ActivityType.PULL_REQUEST).toList();
    }

    private static List<EffectiveActivity> done(List<EffectiveActivity> acts) {
        return acts.stream().filter(a -> a.type() == ActivityType.TICKET && JsonMaps.bool(a.attributes(), "completed")).toList();
    }

    private static double points(EffectiveActivity a) {
        return Optional.ofNullable(JsonMaps.num(a.attributes(), "storyPoints")).orElse(1.0);
    }

    private static Map<Persona, Double> medianBy(Map<Long, List<EffectiveActivity>> byPerson, Map<Long, Persona> persona,
                                                 Function<List<EffectiveActivity>, Double> metric) {
        Map<Persona, List<Double>> vals = new EnumMap<>(Persona.class);
        byPerson.forEach((id, acts) -> {
            Persona p = persona.get(id);
            Double v = metric.apply(acts);
            if (p != null && v != null) vals.computeIfAbsent(p, k -> new ArrayList<>()).add(v);
        });
        Map<Persona, Double> out = new EnumMap<>(Persona.class);
        vals.forEach((p, list) -> {
            Collections.sort(list);
            out.put(p, list.get(list.size() / 2));
        });
        return out;
    }
}
