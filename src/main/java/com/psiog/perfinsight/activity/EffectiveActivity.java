package com.psiog.perfinsight.activity;

import com.psiog.perfinsight.ingest.ToolType;
import com.psiog.perfinsight.org.Persona;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Read model = source activity + active manual overrides. Everything downstream (scoring, reports,
 * anomalies, AI) works on this, so edits count, while the source row stays untouched.
 */
public record EffectiveActivity(
        Long id,
        ToolType sourceTool,
        String externalId,
        ActivityType type,
        String title,
        String url,
        Long associateId,
        String associateName,
        Long projectId,
        String projectCode,
        Long sourceProjectId,
        Persona persona,
        AttributionStatus attributionStatus,
        Instant occurredAt,
        LocalDate occurredOn,
        Map<String, Object> attributes,
        ActivityOrigin origin,
        boolean excluded,
        boolean edited,
        List<OverrideDto> overrides,
        Long toolAccountId,
        String actorLabel,
        String manualReason,
        String createdBy,
        Instant createdAt
) {
    public record OverrideDto(Long id, String field, String originalValue, String newValue, String reason,
                              String createdBy, Instant createdAt, boolean active, String revokedBy, Instant revokedAt) {
        public static OverrideDto of(ActivityOverride o) {
            return new OverrideDto(o.getId(), o.getField(), o.getOriginalValue(), o.getNewValue(), o.getReason(),
                    o.getCreatedBy(), o.getCreatedAt(), o.isActive(), o.getRevokedBy(), o.getRevokedAt());
        }
    }

    public Object attr(String key) {
        return attributes == null ? null : attributes.get(key);
    }
}
