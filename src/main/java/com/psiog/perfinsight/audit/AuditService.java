package com.psiog.perfinsight.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repo;
    private final AuditorAware<String> auditorAware;

    @Transactional(propagation = Propagation.REQUIRED)
    public void log(String action, String entityType, Long entityId, Map<String, Object> details) {
        AuditLog l = new AuditLog();
        l.setActor(currentActor());
        l.setAction(action);
        l.setEntityType(entityType);
        l.setEntityId(entityId);
        l.setDetails(details);
        l.setAt(Instant.now());
        repo.save(l);
    }

    public String currentActor() {
        return auditorAware.getCurrentAuditor().orElse("system");
    }
}
