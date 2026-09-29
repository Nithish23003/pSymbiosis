package com.psiog.perfinsight.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {
    List<SyncRun> findTop50ByOrderByIdDesc();
    List<SyncRun> findTop20ByToolConfigIdOrderByIdDesc(Long toolConfigId);
}
