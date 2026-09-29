package com.psiog.perfinsight.insight;

import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.security.AccessScope;
import com.psiog.perfinsight.insight.ai.LlmClient;
import com.psiog.perfinsight.report.ReportDtos.*;
import com.psiog.perfinsight.report.ReportService;
import com.psiog.perfinsight.security.AccessScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Criterion 9: plain-English summaries grounded ONLY in computed facts (scores, traces, notes, flags).
 * The LLM never sees raw source data and never decides a score. Falls back to a template when AI is off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInsightService {

    private static final String SYSTEM = """
            You write short, fair, plain-English summaries of engineering performance data for a manager's
            one-to-one or review conversation. Rules:
            - Use ONLY the JSON facts provided. Never invent numbers, events or causes.
            - Scores are percentiles vs peers in the same role (0-100). Always mention confidence and data gaps.
            - Treat context notes (leave, onboarding, support duty) as context, and anomaly flags as questions to
              discuss - never as accusations or proof of gaming.
            - Do not recommend ratings, pay, promotion or HR action.
            - End with one line stating that this supports, and does not replace, qualitative review.
            - 120-220 words. Headings: Overview, Strengths, Worth discussing, Data caveats.
            """;

    private final ReportService reports;
    private final AnomalyFlagRepository flags;
    private final AiInsightRepository insights;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final AccessScopeService access;

    @Transactional
    public AiInsight summarizeAssociate(Long associateId, LocalDate from, LocalDate to) {
        AssociateReport r = reports.associate(associateId, from, to, null);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("person", r.associate().fullName());
        facts.put("period", from + " to " + to);
        facts.put("overallScore", r.overallScore());
        facts.put("overallNote", r.overallNote());
        List<Map<String, Object>> segs = new ArrayList<>();
        for (ScoreRow s : r.segments()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project", s.projectCode());
            m.put("role", s.persona());
            m.put("dates", s.segmentStart() + " to " + s.segmentEnd());
            m.put("status", s.status());
            m.put("score", s.score());
            m.put("band", s.band());
            m.put("confidence", s.confidence());
            m.put("activeDays", s.activeDays());
            m.put("daysExcludedByNotes", s.excludedDays());
            m.put("measures", reports.explain(s.resultId()).components().stream().map(c -> {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("measure", c.label());
                cm.put("value", c.rawValue());
                cm.put("percentileVsPeers", c.adjustedPercentile());
                cm.put("used", c.included());
                if (!c.included()) cm.put("whyNotUsed", c.exclusionReason());
                return cm;
            }).toList());
            segs.add(m);
        }
        facts.put("segments", segs);
        facts.put("contextNotes", r.contextNotes().stream().map(n -> Map.of("type", n.type(), "from", n.startDate(), "to", n.endDate(),
                "note", Objects.toString(n.note(), ""))).toList());
        facts.put("anomalyFlags", r.anomalies().stream().map(f -> Map.of("title", f.title(), "detail", f.description(), "status", f.status())).toList());
        facts.put("activityCountsByProject", r.activityCountsByProject());
        return generate("ASSOCIATE", associateId, from, to, r.run(), facts, templateAssociate(r));
    }

    @Transactional
    public AiInsight summarizeProject(Long projectId, LocalDate from, LocalDate to) {
        ProjectReport r = reports.project(projectId, from, to, null);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("project", r.project().code() + " - " + r.project().name());
        facts.put("offering", r.project().offering() == null ? null : r.project().offering().name());
        facts.put("period", from + " to " + to);
        facts.put("teamSegments", r.team().size());
        facts.put("personas", r.personas());
        facts.put("measures", r.measures());
        facts.put("activityCounts", r.activityCounts());
        facts.put("unmatchedActivities", r.unmatchedActivities());
        facts.put("insufficientDataSegments", r.team().stream().filter(t -> t.score() == null).count());
        facts.put("anomalyFlags", r.anomalies().stream().map(f -> Map.of("person", f.associateName(), "title", f.title(), "status", f.status())).toList());
        return generate("PROJECT", projectId, from, to, r.run(), facts, templateProject(r));
    }

    @Transactional
    public AiInsight explainFlag(Long flagId) {
        AnomalyFlag f = flags.findById(flagId).orElseThrow(() -> new NotFoundException("AnomalyFlag", flagId));
        access.requireCanSee(f.getAssociate().getId());
        String text;
        String by = "template";
        if (llm.enabled()) {
            try {
                text = llm.complete("""
                        Explain this automatically detected pattern to a manager in 3-4 plain sentences: what was seen,
                        innocent explanations, and one neutral question to ask the engineer. Do not accuse. Use only the facts.
                        """, mapper.writeValueAsString(Map.of("title", f.getTitle(), "detail", f.getDescription(),
                        "metrics", f.getMetrics() == null ? Map.of() : f.getMetrics())));
                by = llm.providerName();
            } catch (Exception e) {
                log.warn("LLM failed, using template: {}", e.getMessage());
                text = f.getDescription() + " Ask about the context before drawing conclusions.";
            }
        } else {
            text = f.getDescription() + " Ask about the context before drawing conclusions.";
        }
        f.setAiExplanation(text);
        AiInsight i = new AiInsight();
        i.setSubjectType("ANOMALY");
        i.setSubjectId(flagId);
        i.setPeriodStart(f.getPeriodStart());
        i.setPeriodEnd(f.getPeriodEnd());
        i.setGeneratedBy(by);
        i.setSummary(text);
        return insights.save(i);
    }

    @Transactional(readOnly = true)
    public List<AiInsight> history(String subjectType, Long subjectId) {
        AccessScope s = access.current();
        switch (subjectType) {
            case "ASSOCIATE" -> access.requireCanSee(subjectId);
            case "ANOMALY" -> {
                if (!s.canEditData()) throw new ForbiddenException("No access");
                access.requireCanSee(flags.findById(subjectId)
                        .orElseThrow(() -> new NotFoundException("AnomalyFlag", subjectId)).getAssociate().getId());
            }
            default -> { if (!s.canEditData()) throw new ForbiddenException("No access"); }
        }
        return insights.findTop10BySubjectTypeAndSubjectIdOrderByIdDesc(subjectType, subjectId);
    }

    private AiInsight generate(String type, Long id, LocalDate from, LocalDate to, RunInfo run,
                               Map<String, Object> facts, String fallback) {
        String summary = fallback;
        String by = "template";
        if (llm.enabled()) {
            try {
                summary = llm.complete(SYSTEM, "FACTS:\n" + mapper.writeValueAsString(facts));
                by = llm.providerName();
            } catch (Exception e) {
                log.warn("LLM summary failed, using template: {}", e.getMessage());
            }
        }
        AiInsight i = new AiInsight();
        i.setSubjectType(type);
        i.setSubjectId(id);
        i.setPeriodStart(from);
        i.setPeriodEnd(to);
        i.setScoringRunId(run.runId());
        i.setPerformanceModel(run.modelKey() + " v" + run.modelVersion());
        i.setGeneratedBy(by);
        i.setSummary(summary);
        i.setFacts(facts);
        return insights.save(i);
    }

    // ---- deterministic fallbacks (also useful as a demo without an API key) ------------------------

    private String templateAssociate(AssociateReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Overview: ").append(r.associate().fullName()).append(" held ").append(r.segments().size())
          .append(" project/role segment(s) between ").append(r.from()).append(" and ").append(r.to()).append(". ");
        if (r.overallScore() != null) sb.append(String.format("Overall position vs peers: %.0f/100. ", r.overallScore()));
        else sb.append("There was not enough data to produce a score. ");
        for (ScoreRow s : r.segments()) sb.append("\n- ").append(s.explanation());
        if (!r.contextNotes().isEmpty()) {
            sb.append("\nContext: ");
            r.contextNotes().forEach(n -> sb.append(n.type()).append(" ").append(n.startDate()).append("-").append(n.endDate()).append("; "));
        }
        if (!r.anomalies().isEmpty()) {
            sb.append("\nWorth discussing: ");
            r.anomalies().forEach(f -> sb.append(f.title()).append("; "));
        }
        sb.append("\nThis summary supports, and does not replace, qualitative review.");
        return sb.toString();
    }

    private String templateProject(ProjectReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Overview: ").append(r.project().code()).append(" had ").append(r.team().size())
          .append(" person/role segments between ").append(r.from()).append(" and ").append(r.to()).append(". ");
        r.personas().forEach(p -> sb.append(p.persona()).append(": ").append(p.scored()).append("/").append(p.segments())
                .append(" scored").append(p.averageScore() == null ? "" : String.format(", average %.0f", p.averageScore())).append(". "));
        sb.append("Activity: ").append(r.activityCounts()).append(". ");
        if (r.unmatchedActivities() > 0) sb.append(r.unmatchedActivities()).append(" activities are not yet linked to a person. ");
        if (!r.anomalies().isEmpty()) sb.append(r.anomalies().size()).append(" pattern(s) flagged for a conversation. ");
        sb.append("\nThis summary supports, and does not replace, qualitative review.");
        return sb.toString();
    }
}
