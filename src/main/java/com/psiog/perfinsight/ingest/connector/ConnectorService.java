package com.psiog.perfinsight.ingest.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.ingest.ConnectionMode;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/** Chooses LIVE vs MOCK per tool config - the rest of the pipeline cannot tell the difference. */
@Service
public class ConnectorService {

    private final Map<ToolType, ToolConnector> connectors = new EnumMap<>(ToolType.class);
    private final CredentialProvider credentials;
    private final ObjectMapper mapper;

    public ConnectorService(List<ToolConnector> all, CredentialProvider credentials, ObjectMapper mapper) {
        all.forEach(c -> connectors.put(c.toolType(), c));
        this.credentials = credentials;
        this.mapper = mapper;
    }

    public ToolConnector connector(ToolType type) {
        ToolConnector c = connectors.get(type);
        if (c == null) throw new IllegalArgumentException("No connector registered for " + type);
        return c;
    }

    public FetchResult fetch(ProjectToolConfig cfg) {
        return switch (cfg.getMode()) {
            case LIVE -> connector(cfg.getToolType()).fetchLive(cfg, cfg.getCursor(), credentials.forConfig(cfg));
            case MOCK -> fetchMock(cfg);
            case FILE -> new FetchResult(List.of(), cfg.getCursor());
        };
    }

    /** Reads settings.mockFile (classpath: or file:) and applies the watermark like a real incremental API would. */
    private FetchResult fetchMock(ProjectToolConfig cfg) {
        String location = cfg.setting("mockFile");
        if (location == null) throw new IllegalStateException("MOCK mode requires settings.mockFile");
        Resource res = location.startsWith("file:") ? new FileSystemResource(location.substring(5))
                : new ClassPathResource(location.replaceFirst("^classpath:", ""));
        try (InputStream in = res.getInputStream()) {
            JsonNode doc = mapper.readTree(in);
            List<SourceRecord> all = connector(cfg.getToolType()).parseDocument(doc);
            Instant since = JsonMaps.toInstant(cfg.getCursor());
            List<SourceRecord> fresh = new ArrayList<>();
            Instant newest = since;
            for (SourceRecord r : all) {
                if (since == null || r.updatedAt() == null || r.updatedAt().isAfter(since)) fresh.add(r);
                if (r.updatedAt() != null && (newest == null || r.updatedAt().isAfter(newest))) newest = r.updatedAt();
            }
            return new FetchResult(fresh, newest == null ? null : newest.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read mock file " + location + ": " + e.getMessage(), e);
        }
    }

    public boolean isPullable(ProjectToolConfig cfg) {
        return cfg.getMode() != ConnectionMode.FILE && cfg.getToolType() != ToolType.MANUAL;
    }
}
