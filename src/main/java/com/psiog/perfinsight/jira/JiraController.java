package com.psiog.perfinsight.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.psiog.perfinsight.jira.JiraDtos.JiraUser;
import com.psiog.perfinsight.jira.JiraDtos.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live Jira lookups.
 *   GET /api/jira/users/nithish.kumar/summary?projectKey=PAY&from=2026-06-01&to=2026-09-30
 */
@RestController
@RequestMapping("/api/jira")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','SERVICE_HEAD','MANAGER')")
public class JiraController {

    private final JiraApiClient jira;
    private final JiraUserStatsService stats;

    /** Check the token works: returns the Jira account the token belongs to. */
    @GetMapping("/connection")
    public Map<String, Object> connection() {
        JsonNode me = jira.myself();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connected", true);
        m.put("accountId", me.path("accountId").asText(null));
        m.put("displayName", me.path("displayName").asText(null));
        m.put("email", me.path("emailAddress").asText(null));
        return m;
    }

    @GetMapping("/projects")
    public List<Map<String, String>> projects() {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (JsonNode p : jira.projects().path("values"))
            out.add(Map.of("key", p.path("key").asText(), "name", p.path("name").asText(), "id", p.path("id").asText()));
        return out;
    }

    /** Candidates for a name, best match first (restricted to people assignable in the project if given). */
    @GetMapping("/users/search")
    public List<JiraUser> search(@RequestParam String name, @RequestParam(required = false) String projectKey) {
        return stats.findUsers(name, projectKey);
    }

    /**
     * Tickets, story points, time logged, commits and PRs for one person in one project.
     * name: "nithish.kumar", "Nithish Kumar" or an email. accountId: to pick one of several matches.
     * gitAliases: his Git login(s) if they differ from his Jira name, e.g. gitAliases=nithishk
     */
    @GetMapping("/users/{name}/summary")
    public UserSummary summary(@PathVariable String name,
                               @RequestParam(required = false) String projectKey,
                               @RequestParam(required = false) String accountId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Boolean includeDev,
                               @RequestParam(required = false) List<String> gitAliases) {
        return stats.summary(name, accountId, projectKey, from, to, includeDev, gitAliases);
    }
}
