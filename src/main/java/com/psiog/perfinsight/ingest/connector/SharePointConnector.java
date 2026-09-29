package com.psiog.perfinsight.ingest.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SharePoint document libraries through Microsoft Graph delta queries - the deltaLink IS the incremental cursor.
 * Auth: client-credentials with an Azure AD app that has Sites.Read.All (application permission).
 * settings: siteId (required), driveId (optional; default document library).
 */
@Slf4j
@Component
public class SharePointConnector extends AbstractConnector {

    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    public SharePointConnector(ObjectMapper mapper, RestClient.Builder restBuilder) {
        super(mapper, restBuilder);
    }

    @Override
    public ToolType toolType() {
        return ToolType.SHAREPOINT;
    }

    @Override
    public FetchResult fetchLive(ProjectToolConfig cfg, String cursor, AppProperties.Credential cred) {
        requireToken(cred, "SharePoint (client secret)");
        String token = accessToken(cred);
        RestClient graph = http().defaultHeader("Authorization", "Bearer " + token).build();

        String url;
        if (cursor != null && cursor.startsWith("https://")) {
            url = cursor; // stored deltaLink -> only changes since last sync
        } else {
            String siteId = Objects.requireNonNull(cfg.setting("siteId"), "settings.siteId is required");
            String driveId = cfg.setting("driveId");
            url = driveId == null ? GRAPH + "/sites/" + siteId + "/drive/root/delta"
                    : GRAPH + "/drives/" + driveId + "/root/delta";
        }

        List<SourceRecord> out = new ArrayList<>();
        String deltaLink = null;
        for (int page = 0; page < 500 && url != null; page++) {
            JsonNode body = graph.get().uri(URI.create(url)).retrieve().body(JsonNode.class);
            if (body == null) break;
            for (JsonNode item : body.path("value")) {
                if (item.has("file") && !item.has("deleted")) out.add(toRecord(item.deepCopy()));
            }
            url = text(body, "@odata.nextLink");
            if (url == null) deltaLink = text(body, "@odata.deltaLink");
        }
        log.info("SharePoint: fetched {} changed documents", out.size());
        return new FetchResult(out, deltaLink == null ? cursor : deltaLink);
    }

    @Override
    public List<SourceRecord> parseDocument(JsonNode doc) {
        List<SourceRecord> out = new ArrayList<>();
        for (JsonNode item : doc.path("value")) {
            if (item.has("file") && !item.has("deleted")) out.add(toRecord(item.deepCopy()));
        }
        return out;
    }

    private SourceRecord toRecord(ObjectNode item) {
        Instant created = JsonMaps.toInstant(text(item, "createdDateTime"));
        Instant modified = JsonMaps.toInstant(text(item, "lastModifiedDateTime"));
        ObjectNode d = mapper.createObjectNode();
        // treat a save within 10 minutes of creation as part of the creation, not a separate contribution
        d.put("modifiedIsCreation", created != null && modified != null && modified.minusSeconds(600).isBefore(created));
        d.put("versionKey", text(item, "id") + "@" + (modified == null ? "" : modified.toString()));
        item.set("_derived", d);
        return new SourceRecord("driveItem", text(item, "id"), modified, toMap(item));
    }

    private String accessToken(AppProperties.Credential cred) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", cred.getClientId());
        form.add("client_secret", cred.getToken());
        form.add("scope", "https://graph.microsoft.com/.default");
        JsonNode res = http().build().post()
                .uri("https://login.microsoftonline.com/" + cred.getTenantId() + "/oauth2/v2.0/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JsonNode.class);
        if (res == null || !res.has("access_token")) throw new IllegalStateException("Could not obtain Graph token");
        return res.get("access_token").asText();
    }
}
