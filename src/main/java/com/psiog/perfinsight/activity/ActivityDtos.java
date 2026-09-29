package com.psiog.perfinsight.activity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

public final class ActivityDtos {
    private ActivityDtos() {}

    /** Manual entry for work that no integrated tool captures. */
    public record ManualActivityRequest(
            @NotNull Long associateId,
            @NotNull Long projectId,
            @NotNull ActivityType type,
            @NotBlank String title,
            String url,
            @NotNull Instant occurredAt,
            Map<String, Object> attributes,
            @NotBlank String reason) {}

    public record OverrideRequest(@NotBlank String field, String value, @NotBlank String reason) {}

    public record RevokeRequest(String reason) {}
}
