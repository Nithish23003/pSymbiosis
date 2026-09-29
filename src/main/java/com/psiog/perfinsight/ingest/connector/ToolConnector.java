package com.psiog.perfinsight.ingest.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;

import java.util.List;

/**
 * Connector SPI. LIVE pulls call {@link #fetchLive}; MOCK mode and JSON export uploads go through
 * {@link #parseDocument} with a document shaped like the real API response - so replacing a mock with a
 * real connection is a config change (mode=LIVE), not a code change.
 */
public interface ToolConnector {

    ToolType toolType();

    /** Incremental pull. cursor is whatever this connector returned last time (null on first run). */
    FetchResult fetchLive(ProjectToolConfig cfg, String cursor, AppProperties.Credential credential);

    /** Parse a mock file / exported JSON document into records (applying the same enrichment as live). */
    List<SourceRecord> parseDocument(JsonNode document);
}
