package com.psiog.perfinsight.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProjectToolConfigRepository extends JpaRepository<ProjectToolConfig, Long> {
    List<ProjectToolConfig> findByProjectId(Long projectId);
    List<ProjectToolConfig> findBySyncEnabledTrue();
}
