package com.psiog.perfinsight.report;

import com.psiog.perfinsight.activity.ActivityRepository;
import com.psiog.perfinsight.activity.ActivityQueryService;
import com.psiog.perfinsight.activity.EffectiveActivity;
import com.psiog.perfinsight.activity.EffectiveActivityService;
import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.common.Period;
import com.psiog.perfinsight.insight.AnomalyFlagRepository;
import com.psiog.perfinsight.notes.ContextNoteController.NoteDto;
import com.psiog.perfinsight.notes.ContextNoteRepository;
import com.psiog.perfinsight.org.*;
import com.psiog.perfinsight.org.OrgDtos.AssignmentDto;
import com.psiog.perfinsight.org.OrgDtos.AssociateDto;
import com.psiog.perfinsight.org.OrgDtos.ProjectDto;
import com.psiog.perfinsight.report.ReportDtos.*;
import com.psiog.perfinsight.scoring.*;
import com.psiog.perfinsight.security.AccessScope;
import com.psiog.perfinsight.security.AccessScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/** Criterion 7: project-in-focus, associate-in-focus, trends and drill-down - all access filtered. */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ScoringService scoring;
    private final ScoreResultRepository results;
    private final ProjectRepository projects;
    private final AssociateRepository associates;
    private final ProjectAssignmentRepository assignments;
    private final ContextNoteRepository notes;
    private final ActivityRepository activities;
    private final EffectiveActivityService effective;
    private final ActivityQueryService activityQuery;
    private final AnomalyFlagRepository flags;
    private final AccessScopeService access;

    @Transactional
    public ProjectReport project(Long projectId, LocalDate from, LocalDate to, Long runId) {
        Period.of(from, to);
        AccessScope scope = access.current();
        Project p = projects.findById(projectId).orElseThrow(() -> new NotFoundException("Project", projectId));
        ScoringRun run = runId != null ? scoring.getRun(runId) : scoring.latestOrRun(from, to);

        List<ScoreResult> rs = results.findByRunIdAndProjectId(run.getId(), projectId).stream()
                .filter(r -> scope.canSee(r.getAssociate().getId())).toList();
        if (rs.isEmpty() && !scope.seesAll() && !scope.canEditData())
            throw new ForbiddenException("You are not on this project in the selected period");

        List<ScoreRow> team = rs.stream().map(ScoreRow::of)
                .sorted(Comparator.comparing(ScoreRow::persona).thenComparing(ScoreRow::associateName)).toList();

        List<PersonaSummary> personas = new ArrayList<>();
        for (Persona persona : Persona.values()) {
            List<ScoreResult> ofPersona = rs.stream().filter(r -> r.getPersona() == persona).toList();
            if (ofPersona.isEmpty()) continue;
            double[] scores = ofPersona.stream().filter(r -> r.getScore() != null).mapToDouble(ScoreResult::getScore).sorted().toArray();
            Map<String, Long> bands = ofPersona.stream().collect(Collectors.groupingBy(ScoreResult::getBand, TreeMap::new, Collectors.counting()));
            personas.add(new PersonaSummary(persona, ofPersona.size(), scores.length,
                    scores.length == 0 ? null : round(Arrays.stream(scores).average().orElse(0)),
                    scores.length == 0 ? null : scores[scores.length / 2], bands));
        }

        Map<String, List<ScoreComponent>> byMeasure = rs.stream().flatMap(r -> r.getComponents().stream())
                .collect(Collectors.groupingBy(ScoreComponent::getMeasureKey, LinkedHashMap::new, Collectors.toList()));
        List<MeasureSummary> measures = byMeasure.entrySet().stream().map(e -> {
            List<ScoreComponent> withData = e.getValue().stream().filter(c -> c.getNormalisedValue() != null).toList();
            return new MeasureSummary(e.getKey(), e.getValue().get(0).getLabel(), withData.size(),
                    withData.isEmpty() ? null : round(withData.stream().mapToDouble(ScoreComponent::getNormalisedValue).average().orElse(0)),
                    withData.stream().anyMatch(c -> c.getAdjustedPercentile() != null)
                            ? round(withData.stream().filter(c -> c.getAdjustedPercentile() != null).mapToDouble(ScoreComponent::getAdjustedPercentile).average().orElse(0))
                            : null);
        }).toList();

        List<EffectiveActivity> acts = effective.effective(activities.search(from, to, null, projectId, null, PageRequest.of(0, 20000)))
                .stream().filter(a -> a.associateId() == null ? scope.seesAll() : scope.canSee(a.associateId())).toList();
        Map<String, Long> counts = acts.stream().collect(Collectors.groupingBy(a -> a.type().name(), TreeMap::new, Collectors.counting()));
        long unmatched = scope.seesAll() || scope.canEditData()
                ? activities.findUnmatched(from, to, projectId, PageRequest.of(0, 5000)).size() : 0;

        // anomaly flags are conversation prompts for managers - not shown on an employee's own view
        List<Flag> fl = !scope.canEditData() ? List.of() : flags.search(from, to, null, projectId).stream()
                .filter(f -> scope.canSee(f.getAssociate().getId()))
                .map(Flag::of).toList();

        return new ProjectReport(ProjectDto.of(p), from, to, RunInfo.of(run), team, personas, measures, counts,
                fl, unmatched, ReportDtos.DISCLAIMER);
    }

    @Transactional
    public AssociateReport associate(Long associateId, LocalDate from, LocalDate to, Long runId) {
        Period.of(from, to);
        access.requireCanSee(associateId);
        AccessScope scope = access.current();
        Associate a = associates.findById(associateId).orElseThrow(() -> new NotFoundException("Associate", associateId));
        ScoringRun run = runId != null ? scoring.getRun(runId) : scoring.latestOrRun(from, to);

        List<ScoreResult> rs = results.findByRunIdAndAssociateId(run.getId(), associateId);
        List<ScoreRow> segs = rs.stream().map(ScoreRow::of).sorted(Comparator.comparing(ScoreRow::segmentStart)).toList();

        // overall = active-day weighted mean of scored segments (each already compared with its own cohort)
        double wsum = 0, days = 0;
        for (ScoreResult r : rs) if (r.getScore() != null) { wsum += r.getScore() * r.getActiveDays(); days += r.getActiveDays(); }
        Double overall = days == 0 ? null : round(wsum / days);
        String overallNote = overall == null ? "No segment had enough data for a score in this period."
                : "Weighted by active days across " + rs.stream().filter(r -> r.getScore() != null).count() + " scored segment(s).";

        List<AssignmentDto> asg = assignments.findOverlappingForAssociate(associateId, from, to).stream().map(AssignmentDto::of).toList();
        boolean self = associateId.equals(scope.associateId()) && !scope.canEditData();
        List<NoteDto> ns = notes.findForAssociate(associateId, from, to).stream()
                .filter(n -> !self || n.isVisibleToAssociate()).map(NoteDto::of).toList();

        List<EffectiveActivity> acts = effective.effective(activities.search(from, to, associateId, null, null, PageRequest.of(0, 20000)));
        Map<String, Map<String, Long>> byProject = acts.stream().collect(Collectors.groupingBy(
                x -> x.projectCode() == null ? "UNASSIGNED" : x.projectCode(), TreeMap::new,
                Collectors.groupingBy(x -> x.type().name(), TreeMap::new, Collectors.counting())));

        List<Flag> fl = !scope.canEditData() ? List.of() : flags.search(from, to, associateId, null).stream().map(Flag::of).toList();

        return new AssociateReport(AssociateDto.of(a), from, to, RunInfo.of(run), overall, overallNote, segs, asg, ns,
                byProject, fl, ReportDtos.DISCLAIMER);
    }

    /** Monthly trend using frozen snapshots - past months keep the model version they were scored with. */
    @Transactional
    public List<TrendPoint> trend(Long projectId, Long associateId, LocalDate from, LocalDate to) {
        AccessScope scope = access.current();
        if (associateId != null) access.requireCanSee(associateId);
        List<TrendPoint> out = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
            LocalDate ms = ym.atDay(1), me = ym.atEndOfMonth();
            if (me.isAfter(LocalDate.now())) me = LocalDate.now();
            if (me.isBefore(ms)) break;
            ScoringRun run = scoring.snapshotFor(ms, me);
            List<ScoreResult> rs = (projectId != null ? results.findByRunIdAndProjectId(run.getId(), projectId)
                    : associateId != null ? results.findByRunIdAndAssociateId(run.getId(), associateId)
                    : results.findByRunId(run.getId())).stream()
                    .filter(r -> scope.canSee(r.getAssociate().getId()))
                    .filter(r -> associateId == null || r.getAssociate().getId().equals(associateId))
                    .toList();
            List<ScoreResult> scored = rs.stream().filter(r -> r.getScore() != null).toList();
            Map<String, Double> byPersona = scored.stream().collect(Collectors.groupingBy(r -> r.getPersona().name(), TreeMap::new,
                    Collectors.averagingDouble(ScoreResult::getScore)));
            byPersona.replaceAll((k, v) -> round(v));
            long acts = rs.stream().mapToLong(ScoreResult::getActivityCount).sum();
            out.add(new TrendPoint(ms, me, run.getId(), run.getModelKey() + " v" + run.getModelVersion(),
                    scored.isEmpty() ? null : round(scored.stream().mapToDouble(ScoreResult::getScore).average().orElse(0)),
                    byPersona, scored.size(), rs.size(), acts));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Explanation explain(Long resultId) {
        ScoreResult r = results.findById(resultId).orElseThrow(() -> new NotFoundException("ScoreResult", resultId));
        access.requireCanSee(r.getAssociate().getId());
        return new Explanation(ScoreRow.of(r), r.getComponents().stream().map(Component::of).toList(),
                r.getNotesApplied(), RunInfo.of(r.getRun()), ReportDtos.METHOD);
    }

    /** Drill-down from a score component to the tickets / PRs / test runs / documents behind it. */
    @Transactional(readOnly = true)
    public List<EffectiveActivity> evidence(Long resultId, String measureKey) {
        ScoreResult r = results.findById(resultId).orElseThrow(() -> new NotFoundException("ScoreResult", resultId));
        access.requireCanSee(r.getAssociate().getId());
        List<Long> ids = r.getComponents().stream()
                .filter(c -> measureKey == null || c.getMeasureKey().equals(measureKey))
                .flatMap(c -> c.getEvidenceActivityIds().stream()).distinct().toList();
        return activityQuery.byIds(ids).stream().sorted(Comparator.comparing(EffectiveActivity::occurredAt).reversed()).toList();
    }

    private static Double round(double d) {
        return Math.round(d * 10.0) / 10.0;
    }
}
