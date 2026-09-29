package com.psiog.perfinsight.notes;

import com.psiog.perfinsight.common.BaseEntity;
import com.psiog.perfinsight.org.Associate;
import com.psiog.perfinsight.org.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Manager-supplied context (criterion 6). When excludeFromActiveDays is true, those working days are
 * removed from the denominator, so leave / onboarding / support duty never drag a score down.
 */
@Entity
@Table(name = "context_note", indexes = @Index(columnList = "associate_id"))
@Getter @Setter @NoArgsConstructor
public class ContextNote extends BaseEntity {
    @ManyToOne(optional = false)
    private Associate associate;

    /** null = applies to every project the person is on */
    @ManyToOne
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContextNoteType type;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(length = 2000)
    private String note;

    private boolean excludeFromActiveDays = true;

    /** Visible to the associate themself (default yes - transparency). */
    private boolean visibleToAssociate = true;
}
