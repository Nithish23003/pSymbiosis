package com.psiog.perfinsight.scoring;

import com.psiog.perfinsight.activity.ActivityRepository;
import com.psiog.perfinsight.activity.EffectiveActivity;
import com.psiog.perfinsight.activity.EffectiveActivityService;
import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.common.Period;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.ingest.ToolType;
import com.psiog.perfinsight.model.CohortScope;
import com.psiog.perfinsight.model.ModelMeasure;
import com.psiog.perfinsight.model.ModelService;
import com.psiog.perfinsight.model.PerformanceModel;
import com.psiog.perfinsight.notes.ContextNote;
import com.psiog.perfinsight.notes.ContextNoteRepository;
import com.psiog.perfinsight.org.*;
import com.psiog.perfinsight.scoring.measure.MeasureCatalog;
import com.psiog.perfinsight.scoring.measure.MeasureDefinition;
import com.psiog.perfinsight.scoring.measure.MeasureDirection;
import com.psiog.perfinsight.scoring.measure.MeasureValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * THE standard method (criterion 5/6) - identical for every project, persona and offering:
 *
 *  1. Split the period into segments: person x project x role, using effective-dated assignments.
 *  2. Active days = working days in the segment minus days covered by manager context notes.
 *  3. For each measure in the model: raw value from effective activities; rate measures are pro-rated
 *     per 20 active days (fair for partial periods).
 *  4. Sparse data guard: a measure needs >= minSamples data points, else it is excluded (not zeroed).
 *  5. Compare with peers: mid-rank percentile within the cohort (same persona, optionally same offering;
 *     falls back to org-wide persona if the cohort is too small). Lower-is-better measures are flipped.
 *  6. Weights: model base weights -> persona/offering/project overrides -> renormalised over the measures
 *     that have data -> no single measure may exceed the cap (default 40%).
 *  7. Score only if >= minMeasures measures from >= minTools tools and enough active days; otherwise
 *     INSUFFICIENT_DATA. Every number above is stored as a trace.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringService {

    private final ModelService modelService;
    private final MeasureCatalog catalog;
    private final ProjectAssignmentRepository assignments;
    private final ActivityRepository activities;
    private final EffectiveActivityService effective;
    private final ContextNoteRepository notes;
    private final ScoringRunRepository runs;
    private final ScoreResultRepository results;
    private final AppProperties props;
    private final AuditService audit;

    /** Working state for one segment. */
    private static final class Seg {
        ProjectAssignment pa;
        Period period;
        List<EffectiveActivity> acts = new ArrayList<>();
        int workingDays, excludedDays, activeDays;
        List<Map<String, Object>> notesApplied = new ArrayList<>();
        Map<String, MeasureValue> values = new LinkedHashMap<>();
        Map<String, Double> normalised = new HashMap<>();
        Map<String, String> excluded = new HashMap<>();
        Long offeringId() { return pa.getProject().getOffering().getId(); }
        Persona persona() { return pa.getPersona(); }
    }

    @Transactional
    public ScoringRun run(LocalDate from, LocalDate to, Long modelId, String kind) {
        Period period = Period.of(from, to);
        PerformanceModel model = modelId == null ? modelService.active() : modelService.find(modelId);
        AppProperties.Scoring cfg = props.getScoring();

        ScoringRun run = new ScoringRun();
        run.setModel(model);
        run.setModelKey(model.getModelKey());
        run.setModelVersion(model.getVersion());
        run.setPeriodStart(from);
        run.setPeriodEnd(to);
        run.setKind(kind);
        run.setStatus("RUNNING");
        run.setTriggeredBy(audit.currentActor());
        run.setStartedAt(Instant.now());
        runs.save(run);

        // ---- 1. segments + their activities ----------------------------------------------------------
        List<EffectiveActivity> all = effective.effective(activities.findAttributableBetween(from, to));
        Map<String, List<EffectiveActivity>> byPersonProject = all.stream()
                .filter(a -> a.associateId() != null && a.projectId() != null)
                .collect(Collectors.groupingBy(a -> a.associateId() + ":" + a.projectId()));
        List<ContextNote> periodNotes = notes.findOverlapping(from, to);

        List<Seg> segs = new ArrayList<>();
        for (ProjectAssignment pa : assignments.findOverlapping(from, to)) {
            Period sp = period.intersect(pa.getValidFrom(), pa.getValidTo());
            if (sp == null) continue;
            Seg s = new Seg();
            s.pa = pa;
            s.period = sp;
            s.acts = byPersonProject.getOrDefault(pa.getAssociate().getId() + ":" + pa.getProject().getId(), List.of())
                    .stream().filter(a -> sp.contains(a.occurredOn())).toList();
            applyNotes(s, periodNotes);
            segs.add(s);
        }

        // ---- 2-4. raw values, pro-rating, sparse guard ------------------------------------------------
        Map<String, ModelMeasure> measures = new LinkedHashMap<>();
        for (ModelMeasure mm : model.getMeasures()) if (mm.isEnabled()) measures.put(mm.getMeasureKey(), mm);

        for (Seg s : segs) {
            for (ModelMeasure mm : measures.values()) {
                MeasureDefinition def = catalog.get(mm.getMeasureKey()).orElse(null);
                if (def == null) { s.excluded.put(mm.getMeasureKey(), "Measure no longer exists"); continue; }
                MeasureValue v = def.compute(s.acts);
                s.values.put(def.key(), v);
                if (v.raw() == null) { s.excluded.put(def.key(), "No data in this segment"); continue; }
                if (v.sampleSize() < mm.getMinSamples()) {
                    s.excluded.put(def.key(), "Sparse data: " + v.sampleSize() + " data point(s), needs " + mm.getMinSamples());
                    continue;
                }
                if (s.activeDays < cfg.getMinActiveDays()) {
                    s.excluded.put(def.key(), "Only " + s.activeDays + " active day(s) in segment");
                    continue;
                }
                double norm = def.rateBased() ? v.raw() * cfg.getWorkingDaysPerNormalisedPeriod() / s.activeDays : v.raw();
                s.normalised.put(def.key(), norm);
            }
        }

        // ---- 5. cohort percentiles ------------------------------------------------------------------
        Map<Seg, Map<String, double[]>> pct = new HashMap<>(); // measure -> {percentile, cohortSize}
        Map<Seg, Map<String, String>> cohortKeys = new HashMap<>();
        for (String key : measures.keySet()) {
            for (Seg s : segs) {
                Double mine = s.normalised.get(key);
                if (mine == null) continue;
                String ck = cohortKey(model.getCohortScope(), s);
                List<Double> peers = cohortValues(segs, key, model.getCohortScope(), s);
                if (peers.size() < cfg.getMinCohortSize() && model.getCohortScope() == CohortScope.PERSONA_OFFERING) {
                    ck = cohortKey(CohortScope.PERSONA, s) + " (offering cohort too small)";
                    peers = cohortValues(segs, key, CohortScope.PERSONA, s);
                }
                if (peers.size() < cfg.getMinCohortSize()) {
                    s.excluded.put(key, "Cohort too small to compare (" + peers.size() + " peer segment(s))");
                    s.normalised.remove(key);
                    continue;
                }
                pct.computeIfAbsent(s, k -> new HashMap<>()).put(key, new double[]{midRankPercentile(peers, mine), peers.size()});
                cohortKeys.computeIfAbsent(s, k -> new HashMap<>()).put(key, ck);
            }
        }

        // ---- 6-7. weights, score, trace -------------------------------------------------------------
        int scored = 0;
        for (Seg s : segs) {
            ScoreResult r = buildResult(run, model, s, measures, pct.getOrDefault(s, Map.of()),
                    cohortKeys.getOrDefault(s, Map.of()));
            if (r.getStatus() == ScoreStatus.SCORED) scored++;
            results.save(r);
        }

        run.setSegmentCount(segs.size());
        run.setScoredCount(scored);
        run.setStatus("SUCCESS");
        run.setFinishedAt(Instant.now());
        run.setMessage("Scored " + scored + " of " + segs.size() + " segments with model "
                + model.getModelKey() + " v" + model.getVersion());
        log.info("Scoring run {}: {}", run.getId(), run.getMessage());
        return run;
    }

    /** Latest successful run for exactly this period; creates one if none exists. */
    @Transactional
    public ScoringRun latestOrRun(LocalDate from, LocalDate to) {
        return runs.findTopByPeriodStartAndPeriodEndAndStatusOrderByIdDesc(from, to, "SUCCESS")
                .orElseGet(() -> run(from, to, null, "ON_DEMAND"));
    }

    /** Trend points use the first SNAPSHOT for the month (frozen), else the latest run, else create a snapshot. */
    @Transactional
    public ScoringRun snapshotFor(LocalDate monthStart, LocalDate monthEnd) {
        return runs.findTopByPeriodStartAndPeriodEndAndKindAndStatusOrderByIdAsc(monthStart, monthEnd, "SNAPSHOT", "SUCCESS")
                .or(() -> runs.findTopByPeriodStartAndPeriodEndAndStatusOrderByIdDesc(monthStart, monthEnd, "SUCCESS"))
                .orElseGet(() -> run(monthStart, monthEnd, null, "SNAPSHOT"));
    }

    public ScoringRun getRun(Long id) {
        return runs.findById(id).orElseThrow(() -> new NotFoundException("ScoringRun", id));
    }

    // ------------------------------------------------------------------------------------------------

    private void applyNotes(Seg s, List<ContextNote> periodNotes) {
        Long associateId = s.pa.getAssociate().getId();
        Long projectId = s.pa.getProject().getId();
        Set<LocalDate> excluded = new HashSet<>();
        for (ContextNote n : periodNotes) {
            if (!n.getAssociate().getId().equals(associateId)) continue;
            if (n.getProject() != null && !n.getProject().getId().equals(projectId)) continue;
            Period overlap = s.period.intersect(n.getStartDate(), n.getEndDate());
            if (overlap == null) continue;
            int days = 0;
            if (n.isExcludeFromActiveDays()) {
                for (LocalDate d = overlap.start(); !d.isAfter(overlap.end()); d = d.plusDays(1)) {
                    if (Period.isWorkingDay(d) && excluded.add(d)) days++;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("noteId", n.getId());
            m.put("type", n.getType().name());
            m.put("from", overlap.start().toString());
            m.put("to", overlap.end().toString());
            m.put("workingDaysExcluded", days);
            m.put("note", n.isVisibleToAssociate() ? n.getNote() : "(manager-only note)");
            s.notesApplied.add(m);
        }
        s.workingDays = s.period.workingDays();
        s.excludedDays = excluded.size();
        s.activeDays = Math.max(0, s.workingDays - s.excludedDays);
    }

    private ScoreResult buildResult(ScoringRun run, PerformanceModel model, Seg s, Map<String, ModelMeasure> measures,
                                    Map<String, double[]> pct, Map<String, String> cohortKeys) {
        AppProperties.Scoring cfg = props.getScoring();
        Map<String, Double> configured = modelService.resolveWeights(model, s.persona(), s.offeringId(), s.pa.getProject().getId());

        // measures that have a percentile AND a weight in this scope
        Map<String, Double> usable = new LinkedHashMap<>();
        for (String key : measures.keySet()) {
            if (!configured.containsKey(key)) {
                if (!s.excluded.containsKey(key)) s.excluded.put(key, "Not weighted for this persona/offering/project");
                continue;
            }
            if (pct.containsKey(key)) usable.put(key, configured.get(key));
        }
        Map<String, Double> eff = capAndNormalise(usable, cfg.getMaxSingleMeasureWeight());

        Set<ToolType> tools = EnumSet.noneOf(ToolType.class);
        for (String k : eff.keySet()) tools.addAll(s.values.get(k).tools());

        ScoreResult r = new ScoreResult();
        r.setRun(run);
        r.setAssociate(s.pa.getAssociate());
        r.setProject(s.pa.getProject());
        r.setPersona(s.persona());
        r.setSegmentStart(s.period.start());
        r.setSegmentEnd(s.period.end());
        r.setWorkingDays(s.workingDays);
        r.setExcludedDays(s.excludedDays);
        r.setActiveDays(s.activeDays);
        r.setNotesApplied(s.notesApplied);
        r.setActivityCount((int) s.acts.stream().filter(a -> !a.excluded()).count());
        r.setMeasuresUsed(eff.size());
        r.setToolsUsed(tools.size());
        r.setCohortKey(cohortKey(model.getCohortScope(), s));

        double score = 0;
        int maxCohort = 0;
        for (String key : measures.keySet()) {
            MeasureDefinition def = catalog.get(key).orElse(null);
            MeasureValue v = s.values.getOrDefault(key, MeasureValue.none());
            ScoreComponent c = new ScoreComponent();
            c.setResult(r);
            c.setMeasureKey(key);
            c.setLabel(def == null ? key : def.label());
            c.setRawValue(round(v.raw()));
            c.setNormalisedValue(round(s.normalised.get(key)));
            c.setSampleSize(v.sampleSize());
            c.setConfiguredWeight(configured.get(key));
            c.setEvidenceActivityIds(new ArrayList<>(v.evidenceIds()));
            c.setTools(v.tools().stream().map(Enum::name).toList());
            double[] p = pct.get(key);
            if (p != null) {
                double adj = def != null && def.direction() == MeasureDirection.LOWER_IS_BETTER ? 100 - p[0] : p[0];
                c.setPercentile(round(p[0]));
                c.setAdjustedPercentile(round(adj));
                c.setCohortSize((int) p[1]);
                c.setCohortKey(cohortKeys.get(key));
                maxCohort = Math.max(maxCohort, (int) p[1]);
                if (eff.containsKey(key)) {
                    c.setIncluded(true);
                    c.setEffectiveWeight(round(eff.get(key)));
                    c.setContribution(round(eff.get(key) * adj));
                    score += eff.get(key) * adj;
                }
            }
            if (!c.isIncluded()) c.setExclusionReason(s.excluded.getOrDefault(key, "Not weighted in this scope"));
            r.getComponents().add(c);
        }
        r.setCohortSize(maxCohort);

        List<String> blockers = new ArrayList<>();
        if (s.activeDays < cfg.getMinActiveDays()) blockers.add("only " + s.activeDays + " active working day(s)");
        if (eff.size() < model.getMinMeasures()) blockers.add(eff.size() + " measure(s) with enough data (needs " + model.getMinMeasures() + ")");
        if (tools.size() < model.getMinTools()) blockers.add("data from " + tools.size() + " tool(s) (needs " + model.getMinTools() + ")");

        if (blockers.isEmpty()) {
            r.setStatus(ScoreStatus.SCORED);
            r.setScore(round(score));
            r.setBand(score >= 70 ? "ABOVE_PEER_RANGE" : score >= 35 ? "WITHIN_PEER_RANGE" : "BELOW_PEER_RANGE");
            r.setConfidence(eff.size() >= model.getMinMeasures() + 2 && s.activeDays >= 15 ? "HIGH"
                    : s.activeDays >= 10 ? "MEDIUM" : "LOW");
        } else {
            r.setStatus(ScoreStatus.INSUFFICIENT_DATA);
            r.setBand("NOT_SCORED");
            r.setConfidence("NONE");
        }
        r.setExplanation(explain(r, blockers));
        return r;
    }

    /** Normalise to 1, then cap any single measure and redistribute the excess proportionally. */
    static Map<String, Double> capAndNormalise(Map<String, Double> weights, double cap) {
        Map<String, Double> w = new LinkedHashMap<>();
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) return w;
        weights.forEach((k, v) -> w.put(k, v / sum));
        if (w.size() * cap < 1.0) return w; // cap impossible to honour; minMeasures check will block scoring anyway
        for (int i = 0; i < 20; i++) {
            double excess = 0, underSum = 0;
            for (double v : w.values()) { if (v > cap) excess += v - cap; else underSum += v; }
            if (excess < 1e-9) break;
            final double ex = excess, us = underSum;
            w.replaceAll((k, v) -> v > cap ? cap : (us <= 0 ? v : v + ex * v / us));
        }
        return w;
    }

    static double midRankPercentile(List<Double> cohort, double mine) {
        long below = cohort.stream().filter(v -> v < mine - 1e-9).count();
        long equal = cohort.stream().filter(v -> Math.abs(v - mine) <= 1e-9).count();
        return (below + 0.5 * equal) / cohort.size() * 100.0;
    }

    private static List<Double> cohortValues(List<Seg> segs, String key, CohortScope scope, Seg me) {
        List<Double> out = new ArrayList<>();
        for (Seg s : segs) {
            if (s.persona() != me.persona()) continue;
            if (scope == CohortScope.PERSONA_OFFERING && !s.offeringId().equals(me.offeringId())) continue;
            Double v = s.normalised.get(key);
            if (v != null) out.add(v);
        }
        return out;
    }

    private static String cohortKey(CohortScope scope, Seg s) {
        return scope == CohortScope.PERSONA_OFFERING
                ? s.persona() + " @ " + s.pa.getProject().getOffering().getCode()
                : s.persona() + " (all offerings)";
    }

    private String explain(ScoreResult r, List<String> blockers) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getAssociate().getFullName()).append(" as ").append(r.getPersona()).append(" on ")
          .append(r.getProject().getCode()).append(" (").append(r.getSegmentStart()).append(" to ").append(r.getSegmentEnd()).append("): ");
        if (r.getStatus() == ScoreStatus.INSUFFICIENT_DATA) {
            sb.append("not scored - ").append(String.join("; ", blockers))
              .append(". This is a data gap, not a judgement; use qualitative review for this segment.");
            return sb.toString();
        }
        sb.append(String.format("%.0f/100 (%s, confidence %s). ", r.getScore(), r.getBand().replace('_', ' ').toLowerCase(), r.getConfidence()));
        sb.append("Based on ").append(r.getMeasuresUsed()).append(" measures from ").append(r.getToolsUsed())
          .append(" tools over ").append(r.getActiveDays()).append(" active working days");
        if (r.getExcludedDays() > 0) sb.append(" (").append(r.getExcludedDays()).append(" days excluded by context notes)");
        sb.append(", compared with ").append(r.getCohortKey()).append(". ");
        List<ScoreComponent> used = r.getComponents().stream().filter(ScoreComponent::isIncluded)
                .sorted(Comparator.comparing(ScoreComponent::getAdjustedPercentile)).toList();
        if (!used.isEmpty()) {
            ScoreComponent hi = used.get(used.size() - 1), lo = used.get(0);
            sb.append("Strongest: ").append(hi.getLabel()).append(String.format(" (%.0fth percentile)", hi.getAdjustedPercentile()));
            if (used.size() > 1) sb.append("; weakest: ").append(lo.getLabel()).append(String.format(" (%.0fth percentile)", lo.getAdjustedPercentile()));
            sb.append(". ");
        }
        long excluded = r.getComponents().stream().filter(c -> !c.isIncluded()).count();
        if (excluded > 0) sb.append(excluded).append(" measure(s) excluded - see components for reasons.");
        return sb.toString();
    }

    private static Double round(Double d) {
        return d == null ? null : Math.round(d * 100.0) / 100.0;
    }
}
