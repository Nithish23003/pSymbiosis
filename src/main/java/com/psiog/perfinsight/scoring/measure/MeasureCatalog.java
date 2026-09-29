package com.psiog.perfinsight.scoring.measure;

import com.psiog.perfinsight.activity.ActivityType;
import com.psiog.perfinsight.activity.EffectiveActivity;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.ingest.ToolType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The measures a team can choose from and weight. Spread across delivery, quality, collaboration and
 * knowledge so no single activity count (commits, lines of code) can dominate (criterion 6).
 * Add a measure = add one entry here; weights and selection stay configuration.
 */
@Component
public class MeasureCatalog {

    private final Map<String, MeasureDefinition> byKey = new LinkedHashMap<>();

    public MeasureCatalog() {
        add(new MeasureDefinition("delivery.throughput_points", "Delivered work (story points)", "Delivery",
                "Story points of tickets completed in the period (tickets without an estimate count as 1). Pro-rated per 20 active days.",
                MeasureDirection.HIGHER_IS_BETTER, true, "points",
                acts -> {
                    List<EffectiveActivity> done = filter(acts, MeasureCatalog::completedTicket);
                    if (done.isEmpty()) return MeasureValue.none();
                    double pts = done.stream().mapToDouble(a -> Optional.ofNullable(JsonMaps.toDouble(a.attr("storyPoints"))).orElse(1.0)).sum();
                    return value(pts, done);
                }));

        add(new MeasureDefinition("delivery.cycle_time_days", "Cycle time (median days)", "Delivery",
                "Median days from start (first 'In Progress', else created) to resolution for completed tickets.",
                MeasureDirection.LOWER_IS_BETTER, false, "days",
                acts -> {
                    List<EffectiveActivity> done = filter(acts, a -> completedTicket(a) && JsonMaps.toDouble(a.attr("cycleTimeHours")) != null);
                    if (done.isEmpty()) return MeasureValue.none();
                    double[] days = done.stream().mapToDouble(a -> JsonMaps.toDouble(a.attr("cycleTimeHours")) / 24.0).sorted().toArray();
                    return value(median(days), done);
                }));

        add(new MeasureDefinition("quality.reopen_rate", "Reopen rate", "Quality",
                "Share of completed tickets that were reopened at least once.",
                MeasureDirection.LOWER_IS_BETTER, false, "ratio",
                acts -> {
                    List<EffectiveActivity> done = filter(acts, MeasureCatalog::completedTicket);
                    if (done.isEmpty()) return MeasureValue.none();
                    long reopened = done.stream().filter(a -> Optional.ofNullable(JsonMaps.toDouble(a.attr("reopenCount"))).orElse(0.0) > 0).count();
                    return value((double) reopened / done.size(), done);
                }));

        add(new MeasureDefinition("code.prs_merged", "Pull requests merged", "Delivery",
                "Authored pull requests merged in the period. Pro-rated per 20 active days.",
                MeasureDirection.HIGHER_IS_BETTER, true, "PRs",
                acts -> {
                    List<EffectiveActivity> prs = filter(acts, a -> a.type() == ActivityType.PULL_REQUEST && JsonMaps.bool(a.attributes(), "merged"));
                    return prs.isEmpty() ? MeasureValue.none() : value(prs.size(), prs);
                }));

        add(new MeasureDefinition("collab.reviews_given", "Code reviews given", "Collaboration",
                "Reviews submitted on other people's pull requests. Pro-rated per 20 active days.",
                MeasureDirection.HIGHER_IS_BETTER, true, "reviews",
                acts -> {
                    List<EffectiveActivity> r = filter(acts, a -> a.type() == ActivityType.CODE_REVIEW);
                    return r.isEmpty() ? MeasureValue.none() : value(r.size(), r);
                }));

        add(new MeasureDefinition("quality.test_executions", "Test executions", "Quality",
                "Test results recorded in the test management tool. Pro-rated per 20 active days.",
                MeasureDirection.HIGHER_IS_BETTER, true, "results",
                acts -> {
                    List<EffectiveActivity> t = filter(acts, a -> a.type() == ActivityType.TEST_RUN);
                    return t.isEmpty() ? MeasureValue.none() : value(t.size(), t);
                }));

        add(new MeasureDefinition("quality.defects_found", "Defects found in testing", "Quality",
                "Defects linked from failed test results. Pro-rated per 20 active days.",
                MeasureDirection.HIGHER_IS_BETTER, true, "defects",
                acts -> {
                    List<EffectiveActivity> t = filter(acts, a -> a.type() == ActivityType.TEST_RUN);
                    if (t.isEmpty()) return MeasureValue.none();
                    double d = t.stream().mapToDouble(a -> Optional.ofNullable(JsonMaps.toDouble(a.attr("defectsLogged"))).orElse(0.0)).sum();
                    return value(d, t);
                }));

        add(new MeasureDefinition("knowledge.doc_contributions", "Documentation contributions", "Knowledge",
                "Documents created or meaningfully edited in SharePoint. Pro-rated per 20 active days.",
                MeasureDirection.HIGHER_IS_BETTER, true, "documents",
                acts -> {
                    List<EffectiveActivity> d = filter(acts, a -> a.type() == ActivityType.DOCUMENT);
                    return d.isEmpty() ? MeasureValue.none() : value(d.size(), d);
                }));
    }

    public Collection<MeasureDefinition> all() {
        return byKey.values();
    }

    public Optional<MeasureDefinition> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    private void add(MeasureDefinition d) {
        byKey.put(d.key(), d);
    }

    // ---- helpers -------------------------------------------------------------------------------

    static boolean completedTicket(EffectiveActivity a) {
        return a.type() == ActivityType.TICKET && JsonMaps.bool(a.attributes(), "completed");
    }

    private static List<EffectiveActivity> filter(List<EffectiveActivity> acts, Predicate<EffectiveActivity> p) {
        return acts.stream().filter(a -> !a.excluded()).filter(p).toList();
    }

    private static MeasureValue value(double raw, List<EffectiveActivity> evidence) {
        return value(raw, evidence, evidence.size());
    }

    private static MeasureValue value(double raw, List<EffectiveActivity> evidence, int sample) {
        Set<ToolType> tools = evidence.stream().map(EffectiveActivity::sourceTool).collect(Collectors.toCollection(() -> EnumSet.noneOf(ToolType.class)));
        return new MeasureValue(raw, sample, evidence.stream().map(EffectiveActivity::id).toList(), tools);
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
