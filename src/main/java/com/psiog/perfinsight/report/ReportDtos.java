package com.psiog.perfinsight.report;

import com.psiog.perfinsight.insight.AnomalyFlag;
import com.psiog.perfinsight.notes.ContextNoteController.NoteDto;
import com.psiog.perfinsight.org.OrgDtos.AssignmentDto;
import com.psiog.perfinsight.org.OrgDtos.AssociateDto;
import com.psiog.perfinsight.org.OrgDtos.ProjectDto;
import com.psiog.perfinsight.org.Persona;
import com.psiog.perfinsight.scoring.ScoreComponent;
import com.psiog.perfinsight.scoring.ScoreResult;
import com.psiog.perfinsight.scoring.ScoreStatus;
import com.psiog.perfinsight.scoring.ScoringRun;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ReportDtos {
    private ReportDtos() {}

    public record RunInfo(Long runId, String modelKey, int modelVersion, String kind, LocalDate periodStart,
                          LocalDate periodEnd, Instant computedAt) {
        public static RunInfo of(ScoringRun r) {
            return new RunInfo(r.getId(), r.getModelKey(), r.getModelVersion(), r.getKind(), r.getPeriodStart(),
                    r.getPeriodEnd(), r.getFinishedAt());
        }
    }

    public record ScoreRow(Long resultId, Long associateId, String associateName, Long projectId, String projectCode,
                           Persona persona, LocalDate segmentStart, LocalDate segmentEnd, int workingDays,
                           int excludedDays, int activeDays, ScoreStatus status, Double score, String band,
                           String confidence, int measuresUsed, int toolsUsed, int activityCount, String cohortKey,
                           String explanation) {
        public static ScoreRow of(ScoreResult r) {
            return new ScoreRow(r.getId(), r.getAssociate().getId(), r.getAssociate().getFullName(),
                    r.getProject().getId(), r.getProject().getCode(), r.getPersona(), r.getSegmentStart(),
                    r.getSegmentEnd(), r.getWorkingDays(), r.getExcludedDays(), r.getActiveDays(), r.getStatus(),
                    r.getScore(), r.getBand(), r.getConfidence(), r.getMeasuresUsed(), r.getToolsUsed(),
                    r.getActivityCount(), r.getCohortKey(), r.getExplanation());
        }
    }

    public record Component(String measureKey, String label, Double rawValue, Double normalisedValue, Double percentile,
                            Double adjustedPercentile, Double configuredWeight, Double effectiveWeight,
                            Double contribution, int sampleSize, int cohortSize, String cohortKey, boolean included,
                            String exclusionReason, int evidenceCount, List<String> tools) {
        public static Component of(ScoreComponent c) {
            return new Component(c.getMeasureKey(), c.getLabel(), c.getRawValue(), c.getNormalisedValue(),
                    c.getPercentile(), c.getAdjustedPercentile(), c.getConfiguredWeight(), c.getEffectiveWeight(),
                    c.getContribution(), c.getSampleSize(), c.getCohortSize(), c.getCohortKey(), c.isIncluded(),
                    c.getExclusionReason(), c.getEvidenceActivityIds() == null ? 0 : c.getEvidenceActivityIds().size(),
                    c.getTools());
        }
    }

    public record Flag(Long id, Long associateId, String associateName, Long projectId, String ruleKey, String severity,
                       String title, String description, Map<String, Object> metrics, int evidenceCount,
                       LocalDate periodStart, LocalDate periodEnd, String status, String reviewedBy, String reviewNote,
                       String aiExplanation) {
        public static Flag of(AnomalyFlag f) {
            return new Flag(f.getId(), f.getAssociate().getId(), f.getAssociate().getFullName(),
                    f.getProject() == null ? null : f.getProject().getId(), f.getRuleKey(), f.getSeverity(),
                    f.getTitle(), f.getDescription(), f.getMetrics(),
                    f.getEvidenceActivityIds() == null ? 0 : f.getEvidenceActivityIds().size(),
                    f.getPeriodStart(), f.getPeriodEnd(), f.getStatus(), f.getReviewedBy(), f.getReviewNote(),
                    f.getAiExplanation());
        }
    }

    public record PersonaSummary(Persona persona, int segments, int scored, Double averageScore, Double medianScore,
                                 Map<String, Long> bands) {}

    public record MeasureSummary(String measureKey, String label, int peopleWithData, Double averageNormalisedValue,
                                 Double averagePercentile) {}

    public record ProjectReport(ProjectDto project, LocalDate from, LocalDate to, RunInfo run, List<ScoreRow> team,
                                List<PersonaSummary> personas, List<MeasureSummary> measures,
                                Map<String, Long> activityCounts, List<Flag> anomalies, long unmatchedActivities,
                                String disclaimer) {}

    public record AssociateReport(AssociateDto associate, LocalDate from, LocalDate to, RunInfo run,
                                  Double overallScore, String overallNote, List<ScoreRow> segments,
                                  List<AssignmentDto> assignmentsInPeriod, List<NoteDto> contextNotes,
                                  Map<String, Map<String, Long>> activityCountsByProject, List<Flag> anomalies,
                                  String disclaimer) {}

    public record TrendPoint(LocalDate monthStart, LocalDate monthEnd, Long runId, String model, Double averageScore,
                             Map<String, Double> averageByPersona, int scoredSegments, int totalSegments, long activities) {}

    public record Explanation(ScoreRow result, List<Component> components, List<Map<String, Object>> notesApplied,
                              RunInfo run, String method) {}

    public static final String DISCLAIMER = "Scores are percentile positions against peers in the same role, from tool data only. "
            + "They support - and never replace - qualitative review. Check confidence, context notes and the evidence before drawing conclusions.";

    public static final String METHOD = "Per person x project x role segment: measures computed from tool data (with manual edits applied); "
            + "count measures pro-rated per 20 active working days (context-note days removed); measures with too few data points excluded; "
            + "each measure converted to a mid-rank percentile within the peer cohort (same role, optionally same offering; lower-is-better measures flipped); "
            + "weighted by the published model (persona/offering/project overrides), renormalised over measures with data and capped per measure; "
            + "a score is only produced with enough measures, tools and active days.";
}
