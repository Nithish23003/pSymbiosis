package com.psiog.perfinsight.org;

import com.psiog.perfinsight.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "team")
@Getter @Setter @NoArgsConstructor
public class Team extends BaseEntity {
    @Column(nullable = false, unique = true)
    private String name;

    @ManyToOne
    private Offering offering;

    /** The Manager (Azure AD role "Manager") who sees this team's data. */
    @ManyToOne
    private Associate manager;
}
