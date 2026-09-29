package com.psiog.perfinsight.scoring;

import com.psiog.perfinsight.insight.AnomalyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/** Freezes last month's results on the 1st so trends never change retroactively (criterion 8). */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotScheduler {

    private final ScoringService scoring;
    private final AnomalyService anomalies;

    @Scheduled(cron = "${app.scoring.snapshot-cron}")
    public void monthlySnapshot() {
        YearMonth last = YearMonth.now().minusMonths(1);
        LocalDate from = last.atDay(1), to = last.atEndOfMonth();
        try {
            scoring.snapshotFor(from, to);
            anomalies.detect(from, to);
            log.info("Monthly snapshot for {} done", last);
        } catch (Exception e) {
            log.error("Monthly snapshot for {} failed: {}", last, e.getMessage(), e);
        }
    }
}
