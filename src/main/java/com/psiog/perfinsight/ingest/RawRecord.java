package com.psiog.perfinsight.ingest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Append-only copy of exactly what a tool returned. A new row is added only when the payload changed. */
@Entity
@Table(name = "raw_record", indexes = @Index(columnList = "toolConfigId,externalId"))
@Getter @Setter @NoArgsConstructor
public class RawRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long toolConfigId;

    @Enumerated(EnumType.STRING)
    private ToolType toolType;

    private String recordKind;

    private String externalId;

    private Instant sourceUpdatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    private String payloadHash;

    private Instant fetchedAt;

    private Long syncRunId;

    /** INTEGRATION | FILE_IMPORT */
    private String arrivedVia;
}
