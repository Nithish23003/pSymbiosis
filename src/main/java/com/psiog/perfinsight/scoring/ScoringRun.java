package com.psiog.perfinsight.scoring;

import com.psiog.perfinsight.model.PerformanceModel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** Immutable scoring snapshot, stamped with the exact model version used (criterion 8). */
@Entity
@Table(name = "scoring_run", indexes = @Index(columnList = "periodStart,periodEnd"))
@Getter @Setter @NoArgsConstructor
public class ScoringRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private PerformanceModel model;

    private String modelKey;
    private int modelVersion;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    /** ON_DEMAND | SNAPSHOT (monthly, feeds trends) */
    private String kind;

    /** RUNNING | SUCCESS | FAILED */
    private String status;

    private String triggeredBy;
    private Instant startedAt;
    private Instant finishedAt;
    private int segmentCount;
    private int scoredCount;

    @Column(length = 2000)
    private String message;
}
