package com.psiog.perfinsight.org;

import com.psiog.perfinsight.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "offering")
@Getter @Setter @NoArgsConstructor
public class Offering extends BaseEntity {
    @Column(nullable = false, unique = true)
    private String code;
    @Column(nullable = false)
    private String name;
    private String description;

    public Offering(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
