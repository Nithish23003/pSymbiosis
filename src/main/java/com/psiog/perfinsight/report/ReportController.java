package com.psiog.perfinsight.report;

import com.psiog.perfinsight.activity.EffectiveActivity;
import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.report.ReportDtos.*;
import com.psiog.perfinsight.security.AccessScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reports;
    private final AccessScopeService access;

    /** Project in focus: team, personas, measures, activity mix, anomalies. */
    @GetMapping("/projects/{id}")
    public ProjectReport project(@PathVariable Long id,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) Long runId) {
        return reports.project(id, from, to, runId);
    }

    @GetMapping("/projects/{id}/trend")
    public List<TrendPoint> projectTrend(@PathVariable Long id,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.trend(id, null, from, to);
    }

    /** Associate in focus: every project and role held in the period. */
    @GetMapping("/associates/{id}")
    public AssociateReport associate(@PathVariable Long id,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) Long runId) {
        return reports.associate(id, from, to, runId);
    }

    @GetMapping("/associates/{id}/trend")
    public List<TrendPoint> associateTrend(@PathVariable Long id,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.trend(null, id, from, to);
    }

    /** Shortcut for the signed-in user (Employees land here). */
    @GetMapping("/me")
    public AssociateReport me(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long me = access.current().associateId();
        if (me == null) throw new ForbiddenException("Your login is not linked to an associate record");
        return reports.associate(me, from, to, null);
    }

    /** How was this result reached? Every weight, percentile, exclusion and note. */
    @GetMapping("/scores/{resultId}/explain")
    public Explanation explain(@PathVariable Long resultId) {
        return reports.explain(resultId);
    }

    /** Underlying tickets / PRs / test runs / documents for a score (optionally one measure). */
    @GetMapping("/scores/{resultId}/evidence")
    public List<EffectiveActivity> evidence(@PathVariable Long resultId, @RequestParam(required = false) String measureKey) {
        return reports.evidence(resultId, measureKey);
    }
}
