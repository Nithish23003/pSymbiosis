package com.psiog.perfinsight.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Append-only log of configuration changes, manual edits, identity links and model publications. */
@Entity
@Table(name = "audit_log", indexes = @Index(columnList = "entityType,entityId"))
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String actor;
    private String action;
    private String entityType;
    private Long entityId;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;
    private Instant at;
}
