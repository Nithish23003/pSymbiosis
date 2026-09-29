package com.psiog.perfinsight.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PerformanceModelRepository extends JpaRepository<PerformanceModel, Long> {
    List<PerformanceModel> findByModelKeyOrderByVersionDesc(String modelKey);
    Optional<PerformanceModel> findTopByModelKeyOrderByVersionDesc(String modelKey);
    Optional<PerformanceModel> findTopByStatusOrderByPublishedAtDesc(ModelStatus status);
    List<PerformanceModel> findAllByOrderByModelKeyAscVersionDesc();
}
