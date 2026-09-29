package com.psiog.perfinsight.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "model_measure")
@Getter @Setter @NoArgsConstructor
public class ModelMeasure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private PerformanceModel model;

    @Column(nullable = false)
    private String measureKey;

    /** Relative weight (normalised at scoring time). */
    private double weight;

    /** Minimum data points before this measure is used for a person (sparse-data guard). */
    private int minSamples = 3;

    private boolean enabled = true;

    @Column(length = 1000)
    private String reasoning;
}
