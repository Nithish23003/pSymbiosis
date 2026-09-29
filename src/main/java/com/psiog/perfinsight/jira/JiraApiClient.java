package com.psiog.perfinsight.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psiog.perfinsight.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Thin client for the Jira Cloud REST API v3 (+ the Development-panel "dev-status" API).
 * Auth: Basic base64(JIRA_EMAIL:JIRA_API_TOKEN) - https://id.atlassian.com/manage-profile/security/api-tokens
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JiraApiClient {

    private final AppProperties props;
    private final RestClient.Builder restBuilder;
    private final ObjectMapper mapper;

    // ---- users / projects ---------------------------------------------------------------------------

    public JsonNode myself() {
        return get(u -> u.path("/rest/api/3/myself").build());
    }

    /** Matches display name and email prefix, e.g. query=nithish.kumar */
    public JsonNode searchUsers(String query) {
        return get(u -> u.path("/rest/api/3/user/search").queryParam("query", query).queryParam("maxResults", 50).build());
    }

    /** Users who can be assigned issues in the project - i.e. "is this person in the project". */
    public JsonNode assignableUsers(String projectKey, String query, String accountId) {
        return get(u -> {
            u.path("/rest/api/3/user/assignable/search").queryParam("project", projectKey).queryParam("maxResults", 50);
            if (accountId != null) u.queryParam("accountId", accountId);
            else if (query != null) u.queryParam("query", query);
            return u.build();
        });
    }

    public JsonNode project(String projectKey) {
        return get(u -> u.path("/rest/api/3/project/{key}").build(projectKey));
    }

    public JsonNode projects() {
        return get(u -> u.path("/rest/api/3/project/search").queryParam("maxResults", 100).build());
    }

    public JsonNode fields() {
        return get(u -> u.path("/rest/api/3/field").build());
    }

    // ---- issues / worklogs ------------------------------------------------------------------------

    /** POST /rest/api/3/search/jql with nextPageToken paging. */
    public List<JsonNode> searchIssues(String jql, List<String> fields, int max) {
        List<JsonNode> out = new ArrayList<>();
        String token = null;
        do {
            ObjectNode body = mapper.createObjectNode();
            body.put("jql", jql);
            body.put("maxResults", Math.min(100, max - out.size()));
            body.set("fields", mapper.valueToTree(fields));
            if (token != null) body.put("nextPageToken", token);
            JsonNode page = post("/rest/api/3/search/jql", body);
            for (JsonNode issue : page.path("issues")) out.add(issue);
            token = page.path("isLast").asBoolean(true) ? null : textOrNull(page, "nextPageToken");
            if (page.path("issues").isEmpty()) token = null;
        } while (token != null && out.size() < max);
        log.debug("JQL [{}] -> {} issues", jql, out.size());
        return out;
    }

    /** All worklogs of an issue (optionally started within [afterMs, beforeMs]). */
    public List<JsonNode> worklogs(String issueKey, Long startedAfterMs, Long startedBeforeMs) {
        List<JsonNode> out = new ArrayList<>();
        int startAt = 0;
        while (true) {
            final int s = startAt;
            JsonNode page = get(u -> {
                u.path("/rest/api/3/issue/{key}/worklog").queryParam("startAt", s).queryParam("maxResults", 1000);
                if (startedAfterMs != null) u.queryParam("startedAfter", startedAfterMs);
                if (startedBeforeMs != null) u.queryParam("startedBefore", startedBeforeMs);
                return u.build(issueKey);
            });
            JsonNode logs = page.path("worklogs");
            logs.forEach(out::add);
            startAt += logs.size();
            if (logs.isEmpty() || startAt >= page.path("total").asInt(0)) break;
        }
        return out;
    }

    // ---- development panel (commits / PRs linked through GitHub/Bitbucket/GitLab for Jira) ----------

    /** Undocumented but widely used endpoint behind the issue "Development" panel. */
    public JsonNode devSummary(String issueId) {
        return get(u -> u.path("/rest/dev-status/latest/issue/summary").queryParam("issueId", issueId).build());
    }

    /** dataType = repository (commits) | pullrequest | branch; applicationType e.g. GitHub, bitbucket, gitlab */
    public JsonNode devDetail(String issueId, String applicationType, String dataType) {
        return get(u -> u.path("/rest/dev-status/latest/issue/detail").queryParam("issueId", issueId)
                .queryParam("applicationType", applicationType).queryParam("dataType", dataType).build());
    }

    public String browseUrl(String issueKey) {
        return baseUrl() + "/browse/" + issueKey;
    }

    // ---- plumbing -----------------------------------------------------------------------------------

    private RestClient client() {
        AppProperties.Credential c = props.getCredentials().get("jira");
        if (c == null || isBlank(c.getUsername()) || isBlank(c.getToken()))
            throw new JiraApiException(0, "Jira credentials missing - set JIRA_EMAIL and JIRA_API_TOKEN");
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (c.getUsername() + ":" + c.getToken()).getBytes(StandardCharsets.UTF_8));
        return restBuilder.clone().baseUrl(baseUrl())
                .defaultHeader("Authorization", auth)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private String baseUrl() {
        String b = props.getJira().getBaseUrl();
        if (isBlank(b)) throw new JiraApiException(0, "Jira base URL missing - set JIRA_BASE_URL (e.g. https://your-domain.atlassian.net)");
        return b.replaceAll("/+$", "");
    }

    private JsonNode get(Function<UriBuilder, URI> uri) {
        try {
            JsonNode n = client().get().uri(uri).retrieve().body(JsonNode.class);
            return n == null ? mapper.createObjectNode() : n;
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new JiraApiException(0, "Cannot reach Jira at " + baseUrl() + ": " + e.getMessage());
        }
    }

    private JsonNode post(String path, JsonNode body) {
        try {
            JsonNode n = client().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
            return n == null ? mapper.createObjectNode() : n;
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new JiraApiException(0, "Cannot reach Jira at " + baseUrl() + ": " + e.getMessage());
        }
    }

    private JiraApiException translate(RestClientResponseException e) {
        int st = e.getStatusCode().value();
        String hint = switch (st) {
            case 401 -> "Jira rejected the credentials (check JIRA_EMAIL / JIRA_API_TOKEN)";
            case 403 -> "The Jira account has no permission for this resource";
            case 404 -> "Not found in Jira (check the project key / issue)";
            case 429 -> "Jira rate limit hit - retry shortly";
            default -> "Jira returned HTTP " + st;
        };
        String body = e.getResponseBodyAsString();
        return new JiraApiException(st, hint + (body.isBlank() ? "" : ": " + body.substring(0, Math.min(400, body.length()))));
    }

    static String textOrNull(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
