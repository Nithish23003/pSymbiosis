package com.psiog.perfinsight.ingest;

import com.psiog.perfinsight.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final SyncService sync;
    private final AppProperties props;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${app.sync.cron}")
    public void scheduledSync() {
        if (!props.getSync().isEnabled()) return;
        if (!running.compareAndSet(false, true)) {
            log.warn("Previous sync still running - skipping this tick");
            return;
        }
        try {
            var runs = sync.syncAll("SCHEDULED");
            log.info("Scheduled sync finished: {} tool configs", runs.size());
        } finally {
            running.set(false);
        }
    }
}
