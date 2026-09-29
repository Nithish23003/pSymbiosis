package com.psiog.perfinsight.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.psiog.perfinsight.activity.Activity;
import com.psiog.perfinsight.activity.ActivityOrigin;
import com.psiog.perfinsight.activity.ActivityRepository;
import com.psiog.perfinsight.activity.AttributionService;
import com.psiog.perfinsight.identity.IdentityResolutionService;
import com.psiog.perfinsight.identity.ToolAccount;
import com.psiog.perfinsight.ingest.connector.SourceRecord;
import com.psiog.perfinsight.ingest.mapping.DefaultMappings;
import com.psiog.perfinsight.ingest.mapping.MappingEngine;
import com.psiog.perfinsight.ingest.mapping.NormalizedActivity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * raw record (append-only, de-duplicated by hash) -> mapping -> canonical activity -> identity -> attribution.
 * Source fields are refreshed from the tool; manual overrides live elsewhere and are never touched here.
 */
@Slf4j
@Service
public class IngestionService {

    public record IngestStats(int rawStored, int created, int updated, int skippedUnchanged) {}

    private final RawRecordRepository raws;
    private final ActivityRepository activities;
    private final MappingEngine mappingEngine;
    private final DefaultMappings mappings;
    private final IdentityResolutionService identity;
    private final AttributionService attribution;
    private final ObjectMapper canonicalMapper;

    public IngestionService(RawRecordRepository raws, ActivityRepository activities, MappingEngine mappingEngine,
                            DefaultMappings mappings, IdentityResolutionService identity,
                            AttributionService attribution, ObjectMapper mapper) {
        this.raws = raws;
        this.activities = activities;
        this.mappingEngine = mappingEngine;
        this.mappings = mappings;
        this.identity = identity;
        this.attribution = attribution;
        this.canonicalMapper = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional
    public IngestStats ingest(ProjectToolConfig cfg, List<SourceRecord> records, Long syncRunId, ActivityOrigin origin) {
        Map<String, Object> spec = mappings.effective(cfg);
        int stored = 0, created = 0, updated = 0, unchanged = 0;

        for (SourceRecord rec : records) {
            String hash = hash(rec.payload());
            var last = raws.findTopByToolConfigIdAndRecordKindAndExternalIdOrderByIdDesc(cfg.getId(), rec.kind(), rec.externalId());
            if (last.isPresent() && hash.equals(last.get().getPayloadHash())) {
                unchanged++;
                continue;
            }
            RawRecord raw = new RawRecord();
            raw.setToolConfigId(cfg.getId());
            raw.setToolType(cfg.getToolType());
            raw.setRecordKind(rec.kind());
            raw.setExternalId(rec.externalId());
            raw.setSourceUpdatedAt(rec.updatedAt());
            raw.setPayload(rec.payload());
            raw.setPayloadHash(hash);
            raw.setFetchedAt(Instant.now());
            raw.setSyncRunId(syncRunId);
            raw.setArrivedVia(origin.name());
            raws.save(raw);
            stored++;

            for (NormalizedActivity n : mappingEngine.map(spec, rec, cfg.getSettings())) {
                boolean isNew = upsert(cfg, n, raw.getId(), origin);
                if (isNew) created++; else updated++;
            }
        }
        log.info("Ingested config {} ({}): raw={}, created={}, updated={}, unchanged={}",
                cfg.getId(), cfg.getToolType(), stored, created, updated, unchanged);
        return new IngestStats(stored, created, updated, unchanged);
    }

    private boolean upsert(ProjectToolConfig cfg, NormalizedActivity n, Long rawId, ActivityOrigin origin) {
        Activity a = activities.findBySourceToolAndExternalIdAndActivityType(cfg.getToolType(), n.externalId(), n.type())
                .orElse(null);
        boolean isNew = a == null;
        if (isNew) {
            a = new Activity();
            a.setSourceTool(cfg.getToolType());
            a.setExternalId(n.externalId());
            a.setActivityType(n.type());
            a.setOrigin(origin);
        }
        a.setTitle(truncate(n.title(), 1000));
        a.setUrl(truncate(n.url(), 1000));
        a.setOccurredAt(n.occurredAt());
        a.setOccurredOn(n.occurredAt().atZone(ZoneOffset.UTC).toLocalDate());
        a.setAttributes(n.attributes());
        a.setRawRecordId(rawId);
        a.setToolConfig(cfg);
        a.setSourceProject(cfg.getProject());

        ToolAccount acc = identity.upsert(cfg.getToolType(), n.actor());
        a.setToolAccount(acc);
        a.setAssociate(acc == null ? null : acc.getAssociate());
        attribution.attribute(a);
        activities.save(a);
        return isNew;
    }

    private String hash(Map<String, Object> payload) {
        try {
            byte[] json = canonicalMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
