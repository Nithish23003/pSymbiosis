package com.psiog.perfinsight.dashboard;

import com.psiog.perfinsight.dashboard.DashboardDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "Dashboard", description = "Metrics from users / jira_story / github_activity + live Jira")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;
    private final JiraStorySyncService jiraSync;
    private final AuthService auth;

    @Operation(summary = "All users (for a login / user picker)")
    @GetMapping("/users")
    public List<UserDto> users() {
        return dashboard.users();
    }

    @Operation(summary = "Team dashboard: KPIs, status pie, points & GitHub by developer, weekly trend, leaderboard, insights")
    @GetMapping("/overview")
    public Overview overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String viewerEmail,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return dashboard.overview(from, to, auth.viewerEmail(authorization, viewerEmail));
    }

    @Operation(summary = "Leaderboard: metrics + score for every developer")
    @GetMapping("/developers")
    public DeveloperDetail developer(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean live,
            @RequestParam(required = false) String jiraName,
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) String viewerEmail,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return dashboard.developer(id, from, to, live, jiraName, projectKey, auth.viewerEmail(authorization, viewerEmail));
    }

    @Operation(summary = "One developer: metrics, score breakdown, stories, PRs, insights (+ live Jira when live=true)")
    @GetMapping("/developers/{id}")
    public DeveloperDetail developer(
            @Parameter(example = "5") @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Also call Jira live (time logged, live tickets)") @RequestParam(defaultValue = "false") boolean live,
            @Parameter(description = "Name as in Jira, default = email before @", example = "nithish.kumar")
            @RequestParam(required = false) String jiraName,
            @Parameter(example = "SCRUM") @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) String viewerEmail) {
        return dashboard.developer(id, from, to, live, jiraName, projectKey, viewerEmail);
    }

    @Operation(summary = "Role-based landing: EMPLOYEE -> own dashboard, others -> team overview")
    @GetMapping("/me")
    public Object me(
            @Parameter(example = "nithish@psiog.com") @RequestParam String email,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean live) {
        DashUser u = dashboard.byEmail(email);
        return "EMPLOYEE".equalsIgnoreCase(u.getRole())
                ? dashboard.developer(u.getId(), from, to, live, null, null, email)
                : dashboard.overview(from, to, email);
    }

    @Operation(summary = "Refresh jira_story from the live Jira project (upsert by story key)")
    @PostMapping("/jira/sync")
    public Map<String, Object> syncFromJira(@Parameter(example = "SCRUM") @RequestParam(required = false) String projectKey) {
        return jiraSync.sync(projectKey);
    }
}