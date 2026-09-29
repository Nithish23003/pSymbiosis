package com.psiog.perfinsight.org;

import com.psiog.perfinsight.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "associate", indexes = @Index(columnList = "email", unique = true))
@Getter @Setter @NoArgsConstructor
public class Associate extends BaseEntity {
    @Column(unique = true)
    private String employeeCode;

    @Column(nullable = false)
    private String fullName;

    /** Corporate email (= Azure AD UPN). Primary key for identity resolution. */
    @Column(nullable = false, unique = true)
    private String email;

    /** Azure AD object id ("oid" claim), optional but preferred for login matching. */
    @Column(unique = true)
    private String azureObjectId;

    @ManyToOne
    private Team team;

    private boolean active = true;
}
