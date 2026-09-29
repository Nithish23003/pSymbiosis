package com.psiog.perfinsight.activity;

import com.psiog.perfinsight.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A human edit layered on top of source data (acceptance criterion 2). Never overwrites the activity row.
 * field: title | occurredAt | activityType | projectId | excluded | attributes.&lt;name&gt;
 * Revoking sets active=false; history is always kept.
 */
@Entity
@Table(name = "activity_override", indexes = @Index(columnList = "activity_id,active"))
@Getter @Setter @NoArgsConstructor
public class ActivityOverride extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Activity activity;

    @Column(nullable = false)
    private String field;

    @Column(length = 2000)
    private String originalValue;

    @Column(length = 2000)
    private String newValue;

    @Column(nullable = false, length = 1000)
    private String reason;

    private boolean active = true;

    private String revokedBy;
    private Instant revokedAt;
}
