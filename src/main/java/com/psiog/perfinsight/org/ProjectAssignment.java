package com.psiog.perfinsight.org;

import com.psiog.perfinsight.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Effective-dated role of an associate on a project (acceptance criterion 3).
 * validTo == null means "still current". Activity is attributed using the assignment valid on the event date.
 */
@Entity
@Table(name = "project_assignment", indexes = {
        @Index(columnList = "associate_id"), @Index(columnList = "project_id")})
@Getter @Setter @NoArgsConstructor
public class ProjectAssignment extends BaseEntity {

    @ManyToOne(optional = false)
    private Associate associate;

    @ManyToOne(optional = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Persona persona;

    @Column(nullable = false)
    private LocalDate validFrom;

    private LocalDate validTo;

    private Integer allocationPercent = 100;

    public boolean covers(LocalDate d) {
        return !d.isBefore(validFrom) && (validTo == null || !d.isAfter(validTo));
    }
}
