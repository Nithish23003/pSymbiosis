package com.psiog.perfinsight.scoring;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/** The calculation trace for one measure - "show how each result was reached" (criterion 6). */
@Entity
@Table(name = "score_component")
@Getter @Setter @NoArgsConstructor
public class ScoreComponent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private ScoreResult result;

    private String measureKey;
    private String label;

    /** value in the segment, e.g. 21 points */
    private Double rawValue;
    /** pro-rated per 20 active days for rate measures, else = raw */
    private Double normalisedValue;
    /** position in cohort 0-100 (mid-rank) */
    private Double percentile;
    /** percentile flipped for lower-is-better measures */
    private Double adjustedPercentile;

    private Double configuredWeight;
    private Double effectiveWeight;
    /** effectiveWeight x adjustedPercentile */
    private Double contribution;

    private int sampleSize;
    private int cohortSize;
    private String cohortKey;

    private boolean included;
    @Column(length = 500)
    private String exclusionReason;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> evidenceActivityIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> tools = new ArrayList<>();
}
