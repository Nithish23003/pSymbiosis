package com.psiog.perfinsight.insight;

import com.psiog.perfinsight.org.Associate;
import com.psiog.perfinsight.org.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A pattern worth a human look - never an automatic penalty. */
@Entity
@Table(name = "anomaly_flag", indexes = @Index(columnList = "associate_id,periodStart,periodEnd"))
@Getter @Setter @NoArgsConstructor
public class AnomalyFlag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Associate associate;

    @ManyToOne(fetch = FetchType.LAZY)
    private Project project;

    private String ruleKey;
    /** LOW | MEDIUM | HIGH */
    private String severity;
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metrics;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> evidenceActivityIds = new ArrayList<>();

    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Instant detectedAt;

    /** OPEN | DISMISSED | CONFIRMED */
    private String status = "OPEN";
    private String reviewedBy;
    private Instant reviewedAt;
    @Column(length = 2000)
    private String reviewNote;

    /** Plain-English explanation written by the LLM (optional). */
    @Column(columnDefinition = "text")
    private String aiExplanation;
}
