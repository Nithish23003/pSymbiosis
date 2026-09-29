package com.psiog.perfinsight.identity;

import com.psiog.perfinsight.common.BaseEntity;
import com.psiog.perfinsight.ingest.ToolType;
import com.psiog.perfinsight.org.Associate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** An account as it appears in one tool (Jira accountId, GitHub login, ADO uniqueName ...). */
@Entity
@Table(name = "tool_account", uniqueConstraints = @UniqueConstraint(columnNames = {"toolType", "externalId"}))
@Getter @Setter @NoArgsConstructor
public class ToolAccount extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ToolType toolType;

    @Column(nullable = false)
    private String externalId;

    private String username;
    private String email;
    private String displayName;

    @ManyToOne
    private Associate associate;

    @Enumerated(EnumType.STRING)
    private MatchStatus matchStatus = MatchStatus.UNMATCHED;

    private Double matchConfidence;

    @Column(length = 500)
    private String matchNote;

    private String linkedBy;
    private Instant linkedAt;

    public String label() {
        if (displayName != null) return displayName + " (" + (username != null ? username : externalId) + ")";
        return username != null ? username : externalId;
    }
}
