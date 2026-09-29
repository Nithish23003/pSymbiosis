package com.psiog.perfinsight.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditController {
    private final AuditLogRepository repo;

    @GetMapping
    public List<AuditLog> recent(@RequestParam(required = false) String entityType,
                                 @RequestParam(required = false) Long entityId) {
        if (entityType != null && entityId != null) return repo.findByEntityTypeAndEntityIdOrderByAtDesc(entityType, entityId);
        return repo.findTop200ByOrderByAtDesc();
    }
}
