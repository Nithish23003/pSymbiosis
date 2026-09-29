package com.psiog.perfinsight.model;

import com.psiog.perfinsight.org.Persona;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Scope-specific weight. Null scope fields are wildcards; the most specific matching override wins
 * (project > offering > persona). weight = 0 switches a measure off for that scope
 * (e.g. test executions for Engineers on a project without a QA tool).
 */
@Entity
@Table(name = "model_weight_override")
@Getter @Setter @NoArgsConstructor
public class WeightOverride {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private PerformanceModel model;

    @Column(nullable = false)
    private String measureKey;

    @Enumerated(EnumType.STRING)
    private Persona persona;

    private Long offeringId;

    private Long projectId;

    private double weight;

    @Column(length = 1000)
    private String reasoning;

    public int specificity() {
        return (projectId != null ? 4 : 0) + (offeringId != null ? 2 : 0) + (persona != null ? 1 : 0);
    }

    public boolean matches(Persona p, Long offering, Long project) {
        return (persona == null || persona == p)
                && (offeringId == null || offeringId.equals(offering))
                && (projectId == null || projectId.equals(project));
    }
}
