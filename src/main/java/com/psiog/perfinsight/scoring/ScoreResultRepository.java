package com.psiog.perfinsight.scoring;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ScoreResultRepository extends JpaRepository<ScoreResult, Long> {
    List<ScoreResult> findByRunId(Long runId);
    List<ScoreResult> findByRunIdAndProjectId(Long runId, Long projectId);
    List<ScoreResult> findByRunIdAndAssociateId(Long runId, Long associateId);
}
