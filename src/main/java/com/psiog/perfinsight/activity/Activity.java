package com.psiog.perfinsight.activity;

import com.psiog.perfinsight.common.BaseEntity;
import com.psiog.perfinsight.identity.ToolAccount;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;
import com.psiog.perfinsight.org.Associate;
import com.psiog.perfinsight.org.Persona;
import com.psiog.perfinsight.org.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Canonical, tool-agnostic unit of work. Source fields are only ever changed by the source itself;
 * human edits live in {@link ActivityOverride} and are merged at read time.
 */
@Entity
@Table(name = "activity",
        uniqueConstraints = @UniqueConstraint(columnNames = {"sourceTool", "externalId", "activityType"}),
        indexes = {
                @Index(columnList = "associate_id,occurredOn"),
                @Index(columnList = "project_id,occurredOn"),
                @Index(columnList = "tool_account_id")})
@Getter @Setter @NoArgsConstructor
public class Activity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ToolType sourceTool;

    @Column(nullable = false)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityType activityType;

    @Column(length = 1000)
    private String title;

    @Column(length = 1000)
    private String url;

    @ManyToOne(fetch = FetchType.LAZY)
    private ProjectToolConfig toolConfig;

    @ManyToOne(fetch = FetchType.LAZY)
    private ToolAccount toolAccount;

    /** Resolved person (null while identity is unmatched). */
    @ManyToOne(fetch = FetchType.LAZY)
    private Associate associate;

    /** Project whose tool this came from. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Project sourceProject;

    /** Project the work is attributed to (the one the person held on the event date). */
    @ManyToOne(fetch = FetchType.LAZY)
    private Project project;

    @Enumerated(EnumType.STRING)
    private Persona persona;

    @Enumerated(EnumType.STRING)
    private AttributionStatus attributionStatus;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private LocalDate occurredOn;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> attributes = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityOrigin origin;

    private Long rawRecordId;

    /** For MANUAL entries: why it was added (e.g. "Tool not integrable - client-hosted Jira"). */
    @Column(length = 1000)
    private String manualReason;
}
