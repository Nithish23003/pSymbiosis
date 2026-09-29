package com.psiog.perfinsight.ingest.connector;

import java.time.Instant;
import java.util.Map;

/**
 * One item exactly as the tool returned it (plus an optional "_derived" block computed by the connector).
 * kind lets one tool emit several record shapes (e.g. GitHub "pull_request" and "commit").
 */
public record SourceRecord(String kind, String externalId, Instant updatedAt, Map<String, Object> payload) {}
