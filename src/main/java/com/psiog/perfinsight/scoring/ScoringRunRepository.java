package com.psiog.perfinsight.scoring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScoringRunRepository extends JpaRepository<ScoringRun, Long> {
    Optional<ScoringRun> findTopByPeriodStartAndPeriodEndAndStatusOrderByIdDesc(LocalDate start, LocalDate end, String status);
    Optional<ScoringRun> findTopByPeriodStartAndPeriodEndAndKindAndStatusOrderByIdAsc(LocalDate start, LocalDate end, String kind, String status);
    List<ScoringRun> findByPeriodStartAndPeriodEndOrderByIdDesc(LocalDate start, LocalDate end);
    List<ScoringRun> findTop50ByOrderByIdDesc();
}
