package com.psiog.perfinsight.insight;

import com.psiog.perfinsight.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Map;

/** Stored AI summaries: what was generated, from which facts, by which provider and model version. */
@Entity
@Table(name = "ai_insight", indexes = @Index(columnList = "subjectType,subjectId"))
@Getter @Setter @NoArgsConstructor
public class AiInsight extends BaseEntity {
    /** ASSOCIATE | PROJECT */
    private String subjectType;
    private Long subjectId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Long scoringRunId;
    private String performanceModel;
    /** e.g. "azure-openai:gpt-4o-mini" or "template" */
    private String generatedBy;

    @Column(columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> facts;
}
