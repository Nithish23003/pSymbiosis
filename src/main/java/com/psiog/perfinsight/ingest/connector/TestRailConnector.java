package com.psiog.perfinsight.ingest.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;

/**
 * Test management via the TestRail API v2 (results per run). Swap for Xray/Zephyr by adding another
 * connector for ToolType.TEST_MANAGEMENT with settings.flavour.
 * settings: projectId (required). Record = one test result, enriched with its run and user.
 */
@Slf4j
@Component
public class TestRailConnector extends AbstractConnector {

    private final AppProperties props;

    public TestRailConnector(ObjectMapper mapper, RestClient.Builder restBuilder, AppProperties props) {
        super(mapper, restBuilder);
        this.props = props;
    }

    @Override
    public ToolType toolType() {
        return ToolType.TEST_MANAGEMENT;
    }

    @Override
    public FetchResult fetchLive(ProjectToolConfig cfg, String cursor, AppProperties.Credential cred) {
        requireToken(cred, "TestRail");
        String projectId = Objects.requireNonNull(cfg.setting("projectId"), "settings.projectId is required");
        long since = sinceFrom(cursor, props.getSync().getInitialLookbackDays()).getEpochSecond();
        RestClient tr = http().baseUrl(cfg.getBaseUrl())
                .defaultHeader("Authorization", basic(cred.getUsername(), cred.getToken()))
                .defaultHeader("Content-Type", "application/json").build();

        Map<String, JsonNode> users = new HashMap<>();
        JsonNode u = tr.get().uri("/index.php?/api/v2/get_users/" + projectId).retrieve().body(JsonNode.class);
        for (JsonNode user : list(u, "users")) users.put(user.path("id").asText(), user);

        JsonNode runsNode = tr.get().uri("/index.php?/api/v2/get_runs/" + projectId).retrieve().body(JsonNode.class);
        List<SourceRecord> out = new ArrayList<>();
        Instant newest = null;
        for (JsonNode run : list(runsNode, "runs")) {
            JsonNode results = tr.get().uri("/index.php?/api/v2/get_results_for_run/" + run.path("id").asText()
                    + "&created_after=" + since).retrieve().body(JsonNode.class);
            for (JsonNode r : list(results, "results")) {
                ObjectNode copy = r.deepCopy();
                copy.set("run", run);
                JsonNode user = users.get(r.path("created_by").asText());
                if (user != null) copy.set("user", user);
                SourceRecord rec = toRecord(copy);
                out.add(rec);
                newest = max(newest, rec.updatedAt());
            }
        }
        log.info("TestRail project {}: fetched {} results", projectId, out.size());
        return new FetchResult(out, newest == null ? cursor : newest.toString());
    }

    @Override
    public List<SourceRecord> parseDocument(JsonNode doc) {
        List<SourceRecord> out = new ArrayList<>();
        for (JsonNode r : list(doc, "results")) out.add(toRecord(r.deepCopy()));
        return out;
    }

    private SourceRecord toRecord(ObjectNode r) {
        int status = r.path("status_id").asInt();
        ObjectNode d = mapper.createObjectNode();
        d.put("passed", status == 1);
        d.put("failed", status == 5);
        d.put("defectsLogged", r.path("defects").asText("").isBlank() ? 0 : r.path("defects").asText().split(",").length);
        r.set("_derived", d);
        Instant at = Instant.ofEpochSecond(r.path("created_on").asLong());
        return new SourceRecord("result", r.path("id").asText(), at, toMap(r));
    }

    /** TestRail >= 6.7 wraps lists in {offset,limit,size,<name>:[...]}; older versions return bare arrays. */
    private static Iterable<JsonNode> list(JsonNode n, String name) {
        if (n == null) return List.of();
        if (n.isArray()) return n;
        return n.path(name);
    }
}
