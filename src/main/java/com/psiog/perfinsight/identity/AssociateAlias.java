package com.psiog.perfinsight.identity;

import com.psiog.perfinsight.common.BaseEntity;
import com.psiog.perfinsight.ingest.ToolType;
import com.psiog.perfinsight.org.Associate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Known alternative usernames / emails for an associate (e.g. GitHub login "rk-dev", personal email). */
@Entity
@Table(name = "associate_alias", indexes = @Index(columnList = "aliasValue"))
@Getter @Setter @NoArgsConstructor
public class AssociateAlias extends BaseEntity {
    @ManyToOne(optional = false)
    private Associate associate;

    /** null = applies to every tool */
    @Enumerated(EnumType.STRING)
    private ToolType toolType;

    /** Lower-cased username or email */
    @Column(nullable = false)
    private String aliasValue;

    public AssociateAlias(Associate associate, ToolType toolType, String aliasValue) {
        this.associate = associate;
        this.toolType = toolType;
        this.aliasValue = aliasValue.toLowerCase();
    }
}
