package com.psiog.perfinsight.scoring;

import com.psiog.perfinsight.org.Associate;
import com.psiog.perfinsight.org.Persona;
import com.psiog.perfinsight.org.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One person x project x role x date-segment within a run. Someone who moved mid-period gets one row per
 * segment, each compared with the right cohort.
 */
@Entity
@Table(name = "score_result", indexes = {
        @Index(columnList = "run_id,project_id"), @Index(columnList = "run_id,associate_id")})
@Getter @Setter @NoArgsConstructor
public class ScoreResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private ScoringRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Associate associate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    private Persona persona;

    private LocalDate segmentStart;
    private LocalDate segmentEnd;

    private int workingDays;
    private int excludedDays;
    private int activeDays;

    @Enumerated(EnumType.STRING)
    private ScoreStatus status;

    /** 0-100 weighted percentile vs. cohort; null when INSUFFICIENT_DATA */
    private Double score;

    /** ABOVE_PEER_RANGE | WITHIN_PEER_RANGE | BELOW_PEER_RANGE | NOT_SCORED */
    private String band;

    /** HIGH | MEDIUM | LOW | NONE */
    private String confidence;

    private int measuresUsed;
    private int toolsUsed;

    private String cohortKey;
    private int cohortSize;

    private int activityCount;

    @Column(columnDefinition = "text")
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> notesApplied = new ArrayList<>();

    @OneToMany(mappedBy = "result", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ScoreComponent> components = new ArrayList<>();
}
