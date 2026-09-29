package com.psiog.perfinsight.ingest.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Jira Cloud (REST v3 /search/jql with nextPageToken) or Jira Server/DC (REST v2 /search with startAt).
 * settings: projectKey (required), apiFlavour CLOUD|SERVER, authType BASIC|BEARER, jql (optional extra filter),
 *           reopenStatuses (comma list, default "Reopened"), inProgressStatuses (default "In Progress").
 */
@Slf4j
@Component
public class JiraConnector extends AbstractConnector {

    private static final DateTimeFormatter JQL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.of("UTC"));
    private final AppProperties props;

    public JiraConnector(ObjectMapper mapper, RestClient.Builder restBuilder, AppProperties props) {
        super(mapper, restBuilder);
        this.props = props;
    }

    @Override
    public ToolType toolType() {
        return ToolType.JIRA;
    }

    @Override
    public FetchResult fetchLive(ProjectToolConfig cfg, String cursor, AppProperties.Credential cred) {
        requireToken(cred, "Jira");
        String key = Objects.requireNonNull(cfg.setting("projectKey"), "settings.projectKey is required for Jira");
        Instant since = sinceFrom(cursor, props.getSync().getInitialLookbackDays());
        String jql = "project = \"" + key + "\" AND updated >= \"" + JQL_DATE.format(since) + "\""
                + (cfg.setting("jql") == null ? "" : " AND (" + cfg.setting("jql") + ")")
                + " ORDER BY updated ASC";
        String auth = "BEARER".equalsIgnoreCase(cfg.setting("authType", "BASIC"))
                ? "Bearer " + cred.getToken() : basic(cred.getUsername(), cred.getToken());
        RestClient client = http().baseUrl(cfg.getBaseUrl()).defaultHeader("Authorization", auth)
                .defaultHeader("Accept", "application/json").build();

        boolean cloud = !"SERVER".equalsIgnoreCase(cfg.setting("apiFlavour", "CLOUD"));
        List<SourceRecord> out = new ArrayList<>();
        Instant newest = null;
        String pageToken = null;
        int startAt = 0;
        for (int page = 0; page < 200; page++) {
            final String token = pageToken;
            final int start = startAt;
            JsonNode body = cloud
                    ? client.get().uri(u -> {
                        u.path("/rest/api/3/search/jql").queryParam("jql", jql).queryParam("maxResults", 100)
                         .queryParam("fields", "*all").queryParam("expand", "changelog");
                        if (token != null) u.queryParam("nextPageToken", token);
                        return u.build();
                    }).retrieve().body(JsonNode.class)
                    : client.get().uri(u -> u.path("/rest/api/2/search").queryParam("jql", jql)
                        .queryParam("maxResults", 100).queryParam("startAt", start)
                        .queryParam("expand", "changelog").build())
                      .retrieve().body(JsonNode.class);
            if (body == null) break;
            JsonNode issues = body.path("issues");
            for (JsonNode issue : issues) {
                SourceRecord r = toRecord(issue, cfg);
                out.add(r);
                newest = max(newest, r.updatedAt());
            }
            if (cloud) {
                pageToken = text(body, "nextPageToken");
                if (pageToken == null || body.path("isLast").asBoolean(false)) break;
            } else {
                startAt += issues.size();
                if (issues.isEmpty() || startAt >= body.path("total").asInt(0)) break;
            }
        }
        log.info("Jira {}: fetched {} issues since {}", key, out.size(), since);
        return new FetchResult(out, newest == null ? cursor : newest.toString());
    }

    @Override
    public List<SourceRecord> parseDocument(JsonNode document) {
        List<SourceRecord> out = new ArrayList<>();
        JsonNode issues = document.has("issues") ? document.get("issues") : document;
        for (JsonNode issue : issues) out.add(toRecord(issue, null));
        return out;
    }

    private SourceRecord toRecord(JsonNode issue, ProjectToolConfig cfg) {
        ObjectNode copy = issue.deepCopy();
        copy.set("_derived", mapper.valueToTree(derive(issue, cfg)));
        Instant updated = JsonMaps.toInstant(text(issue.path("fields"), "updated"));
        return new SourceRecord("issue", text(issue, "key"), updated, toMap(copy));
    }

    /** Changelog-derived facts used by fairness measures (cycle time from first In Progress, reopen count). */
    private Map<String, Object> derive(JsonNode issue, ProjectToolConfig cfg) {
        Set<String> reopen = csv(cfg == null ? null : cfg.setting("reopenStatuses"), "Reopened");
        Set<String> inProgress = csv(cfg == null ? null : cfg.setting("inProgressStatuses"), "In Progress");
        int reopenCount = 0;
        Instant firstInProgress = null;
        for (JsonNode h : issue.path("changelog").path("histories")) {
            Instant at = JsonMaps.toInstant(text(h, "created"));
            for (JsonNode item : h.path("items")) {
                if (!"status".equalsIgnoreCase(text(item, "field"))) continue;
                String to = Optional.ofNullable(text(item, "toString")).orElse("").toLowerCase(Locale.ROOT);
                if (reopen.contains(to)) reopenCount++;
                if (inProgress.contains(to) && (firstInProgress == null || (at != null && at.isBefore(firstInProgress))))
                    firstInProgress = at;
            }
        }
        Map<String, Object> d = new HashMap<>();
        d.put("reopenCount", reopenCount);
        d.put("firstInProgressAt", firstInProgress == null ? null : firstInProgress.toString());
        return d;
    }

    private static Set<String> csv(String v, String def) {
        Set<String> s = new HashSet<>();
        for (String p : (v == null ? def : v).split(",")) s.add(p.trim().toLowerCase(Locale.ROOT));
        return s;
    }
}
