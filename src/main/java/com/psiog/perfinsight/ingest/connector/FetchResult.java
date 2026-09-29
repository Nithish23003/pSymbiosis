package com.psiog.perfinsight.ingest.connector;

import java.util.List;

/** Records pulled plus the cursor to store for the next incremental pull. */
public record FetchResult(List<SourceRecord> records, String nextCursor) {}
