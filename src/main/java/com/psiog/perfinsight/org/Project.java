package com.psiog.perfinsight.org;

import com.psiog.perfinsight.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "project")
@Getter @Setter @NoArgsConstructor
public class Project extends BaseEntity {
    @Column(nullable = false, unique = true)
    private String code;
    @Column(nullable = false)
    private String name;
    private String description;

    @ManyToOne(optional = false)
    private Offering offering;

    private boolean active = true;
}
