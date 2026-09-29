package com.psiog.perfinsight.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psiog.perfinsight.common.BadRequestException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.ingest.connector.ConnectorService;
import com.psiog.perfinsight.ingest.connector.SourceRecord;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Criterion 1 "import of their exports": CSV rows (kind="row") are mapped with the "row" entries of the
 * tool's mapping; JSON exports are parsed by the tool's connector exactly like a live response.
 */
@Service
@RequiredArgsConstructor
public class FileImportService {

    private final ProjectToolConfigRepository configs;
    private final ConnectorService connectors;
    private final SyncService sync;
    private final ObjectMapper mapper;

    public SyncRun importFile(Long configId, MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return importStream(configId, Optional.ofNullable(file.getOriginalFilename()).orElse("upload"), in);
        } catch (java.io.IOException e) {
            throw new BadRequestException("Could not read upload: " + e.getMessage());
        }
    }

    public SyncRun importStream(Long configId, String fileName, InputStream in) {
        ProjectToolConfig cfg = configs.findById(configId).orElseThrow(() -> new NotFoundException("ToolConfig", configId));
        String name = fileName.toLowerCase(Locale.ROOT);
        try {
            List<SourceRecord> records;
            if (name.endsWith(".csv")) {
                records = parseCsv(in, cfg.setting("csvIdColumn"));
            } else if (name.endsWith(".json")) {
                JsonNode doc = mapper.readTree(in);
                records = connectors.connector(cfg.getToolType()).parseDocument(doc);
            } else {
                throw new BadRequestException("Upload a .csv or .json export");
            }
            return sync.ingestUploaded(cfg, records, fileName);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Could not read file: " + e.getMessage());
        }
    }

    private List<SourceRecord> parseCsv(InputStream in, String idColumn) throws Exception {
        List<SourceRecord> out = new ArrayList<>();
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setAllowMissingColumnNames(true).build();
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(r, fmt)) {
            Instant now = Instant.now();
            for (CSVRecord row : parser) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String h : parser.getHeaderNames()) {
                    if (h == null || h.isBlank()) continue;
                    String v = row.isSet(h) ? row.get(h) : null;
                    m.put(h.replace("﻿", ""), v == null || v.isBlank() ? null : v);
                }
                String id = idColumn != null && m.get(idColumn) != null ? m.get(idColumn).toString()
                        : "row-" + Integer.toHexString(m.hashCode());
                out.add(new SourceRecord("row", id, now, m));
            }
        }
        return out;
    }
}
