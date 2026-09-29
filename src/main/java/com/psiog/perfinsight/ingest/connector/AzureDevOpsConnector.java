package com.psiog.perfinsight.ingest.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Azure DevOps Boards work items (WIQL for changed ids, then batched GET). PAT auth.
 * settings: organization, project (required). baseUrl default https://dev.azure.com
 */
@Slf4j
@Component
public class AzureDevOpsConnector extends AbstractConnector {

    private final AppProperties props;

    public AzureDevOpsConnector(ObjectMapper mapper, RestClient.Builder restBuilder, AppProperties props) {
        super(mapper, restBuilder);
        this.props = props;
    }

    @Override
    public ToolType toolType() {
        return ToolType.AZURE_DEVOPS;
    }

    @Override
    public FetchResult fetchLive(ProjectToolConfig cfg, String cursor, AppProperties.Credential cred) {
        requireToken(cred, "Azure DevOps");
        String org = Objects.requireNonNull(cfg.setting("organization"), "settings.organization is required");
        String project = Objects.requireNonNull(cfg.setting("project"), "settings.project is required");
        String base = cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank() ? "https://dev.azure.com" : cfg.getBaseUrl();
        Instant since = sinceFrom(cursor, props.getSync().getInitialLookbackDays()).truncatedTo(ChronoUnit.SECONDS);

        RestClient ado = http().baseUrl(base + "/" + org + "/" + project)
                .defaultHeader("Authorization", basic("", cred.getToken()))
                .build();

        String wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = @project "
                + "AND [System.ChangedDate] >= '" + since + "' ORDER BY [System.ChangedDate] ASC";
        JsonNode res = ado.post().uri("/_apis/wit/wiql?api-version=7.1&timePrecision=true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", wiql))
                .retrieve().body(JsonNode.class);

        List<Integer> ids = new ArrayList<>();
        if (res != null) for (JsonNode wi : res.path("workItems")) ids.add(wi.path("id").asInt());

        List<SourceRecord> out = new ArrayList<>();
        Instant newest = null;
        for (int i = 0; i < ids.size(); i += 200) {
            String batch = String.join(",", ids.subList(i, Math.min(ids.size(), i + 200)).stream().map(String::valueOf).toList());
            JsonNode items = ado.get().uri("/_apis/wit/workitems?ids=" + batch + "&$expand=links&api-version=7.1")
                    .retrieve().body(JsonNode.class);
            if (items == null) continue;
            for (JsonNode wi : items.path("value")) {
                SourceRecord r = toRecord(wi);
                out.add(r);
                newest = max(newest, r.updatedAt());
            }
        }
        log.info("ADO {}/{}: fetched {} work items since {}", org, project, out.size(), since);
        return new FetchResult(out, newest == null ? cursor : newest.toString());
    }

    @Override
    public List<SourceRecord> parseDocument(JsonNode doc) {
        List<SourceRecord> out = new ArrayList<>();
        JsonNode items = doc.has("value") ? doc.get("value") : doc;
        for (JsonNode wi : items) out.add(toRecord(wi));
        return out;
    }

    private SourceRecord toRecord(JsonNode wi) {
        Instant changed = JsonMaps.toInstant(text(wi.path("fields"), "System.ChangedDate"));
        return new SourceRecord("workitem", wi.path("id").asText(), changed, toMap(wi));
    }
}
