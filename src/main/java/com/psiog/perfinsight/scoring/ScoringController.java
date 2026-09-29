package com.psiog.perfinsight.scoring;

import com.psiog.perfinsight.insight.AnomalyService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/scoring")
@RequiredArgsConstructor
public class ScoringController {

    private final ScoringService scoring;
    private final ScoringRunRepository runs;
    private final AnomalyService anomalies;

    public record RunRequest(@NotNull LocalDate from, @NotNull LocalDate to, Long modelId, Boolean detectAnomalies) {}

    public record RunDto(Long id, String modelKey, int modelVersion, LocalDate periodStart, LocalDate periodEnd,
                         String kind, String status, String triggeredBy, Instant startedAt, Instant finishedAt,
                         int segmentCount, int scoredCount, String message) {
        static RunDto of(ScoringRun r) {
            return new RunDto(r.getId(), r.getModelKey(), r.getModelVersion(), r.getPeriodStart(), r.getPeriodEnd(),
                    r.getKind(), r.getStatus(), r.getTriggeredBy(), r.getStartedAt(), r.getFinishedAt(),
                    r.getSegmentCount(), r.getScoredCount(), r.getMessage());
        }
    }

    /** Creates a NEW immutable run (older runs, and the model version they used, are kept). */
    @PostMapping("/runs")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE_HEAD')")
    public RunDto run(@RequestBody RunRequest r) {
        ScoringRun run = scoring.run(r.from(), r.to(), r.modelId(), "ON_DEMAND");
        if (!Boolean.FALSE.equals(r.detectAnomalies())) anomalies.detect(r.from(), r.to());
        return RunDto.of(run);
    }

    @GetMapping("/runs")
    public List<RunDto> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        List<ScoringRun> list = from != null && to != null ? runs.findByPeriodStartAndPeriodEndOrderByIdDesc(from, to)
                : runs.findTop50ByOrderByIdDesc();
        return list.stream().map(RunDto::of).toList();
    }

    @GetMapping("/runs/{id}")
    public RunDto get(@PathVariable Long id) {
        return RunDto.of(scoring.getRun(id));
    }
}
