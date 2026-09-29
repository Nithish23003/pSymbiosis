package com.psiog.perfinsight.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByEntityTypeAndEntityIdOrderByAtDesc(String entityType, Long entityId);
    List<AuditLog> findTop200ByOrderByAtDesc();
}
