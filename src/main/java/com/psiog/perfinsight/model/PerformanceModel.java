package com.psiog.perfinsight.model;

import com.psiog.perfinsight.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Criterion 5 + 8. A versioned, documented performance model. Teams choose measures and weights
 * (per persona / offering / project via overrides) but the scoring METHOD is fixed and shared.
 */
@Entity
@Table(name = "performance_model", uniqueConstraints = @UniqueConstraint(columnNames = {"modelKey", "version"}))
@Getter @Setter @NoArgsConstructor
public class PerformanceModel extends BaseEntity {

    @Column(nullable = false)
    private String modelKey;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModelStatus status = ModelStatus.DRAFT;

    /** What "good" and "poor" mean for this model, in the team's words. */
    @Column(columnDefinition = "text")
    private String documentation;

    /** Why these measures and weights were chosen. */
    @Column(columnDefinition = "text")
    private String rationale;

    /** What changed from the previous version. */
    @Column(length = 2000)
    private String changeNote;

    @Enumerated(EnumType.STRING)
    private CohortScope cohortScope = CohortScope.PERSONA;

    /** A score is only produced when at least this many measures have enough data. */
    private int minMeasures = 3;

    /** ...drawn from at least this many different tools. */
    private int minTools = 2;

    private Instant publishedAt;
    private String publishedBy;

    @OneToMany(mappedBy = "model", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ModelMeasure> measures = new ArrayList<>();

    @OneToMany(mappedBy = "model", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<WeightOverride> overrides = new ArrayList<>();
}
