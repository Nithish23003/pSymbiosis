package com.psiog.perfinsight.insight;

import com.psiog.perfinsight.report.ReportDtos.Flag;
import com.psiog.perfinsight.security.AccessScope;
import com.psiog.perfinsight.security.AccessScopeService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class InsightController {

    private final AnomalyService anomalies;
    private final AnomalyFlagRepository flags;
    private final AiInsightService ai;
    private final AccessScopeService access;

    public record DetectRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}
    public record ReviewRequest(@NotBlank String status, String note) {}

    @GetMapping("/anomalies")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE_HEAD','MANAGER')")
    @Transactional(readOnly = true)
    public List<Flag> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) Long associateId,
                           @RequestParam(required = false) Long projectId) {
        AccessScope s = access.current();
        return flags.search(from, to, associateId, projectId).stream()
                .filter(f -> s.canSee(f.getAssociate().getId())).map(Flag::of).toList();
    }

    @PostMapping("/anomalies/detect")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE_HEAD')")
    @Transactional
    public List<Flag> detect(@RequestBody DetectRequest r) {
        return anomalies.detect(r.from(), r.to()).stream().map(Flag::of).toList();
    }

    @PostMapping("/anomalies/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE_HEAD','MANAGER')")
    @Transactional
    public Flag review(@PathVariable Long id, @RequestBody ReviewRequest r) {
        AnomalyFlag f = flags.findById(id).orElseThrow();
        access.requireCanSee(f.getAssociate().getId());
        return Flag.of(anomalies.review(id, r.status(), r.note()));
    }

    @PostMapping("/anomalies/{id}/explain")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE_HEAD','MANAGER')")
    public AiInsight explain(@PathVariable Long id) {
        return ai.explainFlag(id);
    }

    @PostMapping("/associates/{id}/summary")
    public AiInsight associateSummary(@PathVariable Long id,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ai.summarizeAssociate(id, from, to);
    }

    @PostMapping("/projects/{id}/summary")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE_HEAD','MANAGER')")
    public AiInsight projectSummary(@PathVariable Long id,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ai.summarizeProject(id, from, to);
    }

    @GetMapping("/history")
    public List<AiInsight> history(@RequestParam String subjectType, @RequestParam Long subjectId) {
        return ai.history(subjectType, subjectId);
    }
}
