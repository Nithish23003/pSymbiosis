package com.psiog.perfinsight.ingest;

import com.psiog.perfinsight.activity.ActivityOrigin;
import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.ingest.connector.ConnectorService;
import com.psiog.perfinsight.ingest.connector.FetchResult;
import com.psiog.perfinsight.ingest.connector.SourceRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Criterion 8: incremental sync - each tool config keeps its own watermark / delta cursor. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final ProjectToolConfigRepository configs;
    private final SyncRunRepository runs;
    private final ConnectorService connectors;
    private final IngestionService ingestion;
    private final AuditService audit;

    public SyncRun sync(Long configId, String trigger) {
        ProjectToolConfig cfg = configs.findById(configId).orElseThrow(() -> new NotFoundException("ToolConfig", configId));
        SyncRun run = start(cfg, trigger);
        try {
            FetchResult fr = connectors.fetch(cfg);
            IngestionService.IngestStats st = ingestion.ingest(cfg, fr.records(), run.getId(), ActivityOrigin.INTEGRATION);
            cfg.setCursor(fr.nextCursor());
            finish(cfg, run, fr.records().size(), st, "SUCCESS", "OK");
        } catch (Exception e) {
            log.error("Sync failed for config {}: {}", configId, e.getMessage(), e);
            finish(cfg, run, 0, null, "FAILED", e.getMessage());
        }
        return run;
    }

    /** Used by file imports: records arrive by upload rather than a pull. */
    public SyncRun ingestUploaded(ProjectToolConfig cfg, List<SourceRecord> records, String fileName) {
        SyncRun run = start(cfg, "FILE_IMPORT");
        try {
            IngestionService.IngestStats st = ingestion.ingest(cfg, records, run.getId(), ActivityOrigin.FILE_IMPORT);
            finish(cfg, run, records.size(), st, "SUCCESS", "Imported " + fileName);
        } catch (Exception e) {
            log.error("Import failed for config {}: {}", cfg.getId(), e.getMessage(), e);
            finish(cfg, run, records.size(), null, "FAILED", e.getMessage());
        }
        return run;
    }

    public List<SyncRun> syncAll(String trigger) {
        List<SyncRun> out = new ArrayList<>();
        for (ProjectToolConfig cfg : configs.findBySyncEnabledTrue()) {
            if (connectors.isPullable(cfg)) out.add(sync(cfg.getId(), trigger));
        }
        return out;
    }

    /** Reset watermark so the next sync re-pulls everything (history is kept; unchanged payloads are skipped). */
    public void resetCursor(Long configId) {
        ProjectToolConfig cfg = configs.findById(configId).orElseThrow(() -> new NotFoundException("ToolConfig", configId));
        cfg.setCursor(null);
        configs.save(cfg);
        audit.log("SYNC_CURSOR_RESET", "ProjectToolConfig", configId, java.util.Map.of());
    }

    private SyncRun start(ProjectToolConfig cfg, String trigger) {
        SyncRun run = new SyncRun();
        run.setToolConfigId(cfg.getId());
        run.setTrigger(trigger);
        run.setTriggeredBy(audit.currentActor());
        run.setStartedAt(Instant.now());
        run.setStatus("RUNNING");
        run.setCursorBefore(cfg.getCursor());
        return runs.save(run);
    }

    private void finish(ProjectToolConfig cfg, SyncRun run, int fetched, IngestionService.IngestStats st, String status, String msg) {
        run.setFinishedAt(Instant.now());
        run.setStatus(status);
        run.setRecordsFetched(fetched);
        if (st != null) {
            run.setRawStored(st.rawStored());
            run.setActivitiesCreated(st.created());
            run.setActivitiesUpdated(st.updated());
        }
        run.setCursorAfter(cfg.getCursor());
        run.setMessage(msg == null ? null : msg.substring(0, Math.min(3900, msg.length())));
        runs.save(run);
        cfg.setLastSyncAt(run.getFinishedAt());
        cfg.setLastSyncStatus(status);
        cfg.setLastSyncMessage(run.getMessage() == null ? null : run.getMessage().substring(0, Math.min(1900, run.getMessage().length())));
        configs.save(cfg);
    }
}
