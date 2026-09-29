package com.psiog.perfinsight.ingest.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.config.AppProperties;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

public abstract class AbstractConnector implements ToolConnector {

    protected static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    protected final ObjectMapper mapper;
    protected final RestClient.Builder restBuilder;

    protected AbstractConnector(ObjectMapper mapper, RestClient.Builder restBuilder) {
        this.mapper = mapper;
        this.restBuilder = restBuilder;
    }

    /** Always clone: the injected builder is shared, and baseUrl/defaultHeader mutate it. */
    protected RestClient.Builder http() {
        return restBuilder.clone();
    }

    protected Map<String, Object> toMap(JsonNode node) {
        return mapper.convertValue(node, MAP);
    }

    protected static String basic(String user, String secret) {
        String raw = (user == null ? "" : user) + ":" + (secret == null ? "" : secret);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    protected static void requireToken(AppProperties.Credential c, String what) {
        if (c == null || c.getToken() == null || c.getToken().isBlank())
            throw new IllegalStateException("No credential token configured for " + what + " (see app.credentials in application.yml)");
    }

    /** Parse cursor as an instant, default to lookback. Small overlap avoids missing late writes; duplicates are de-duplicated by hash. */
    protected static Instant sinceFrom(String cursor, int lookbackDays) {
        Instant i = JsonMaps.toInstant(cursor);
        return i == null ? Instant.now().minus(Duration.ofDays(lookbackDays)) : i.minus(Duration.ofMinutes(5));
    }

    protected static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    protected static Instant max(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
