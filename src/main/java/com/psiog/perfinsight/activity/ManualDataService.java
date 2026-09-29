package com.psiog.perfinsight.activity;

import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.BadRequestException;
import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.ingest.ToolType;
import com.psiog.perfinsight.org.Associate;
import com.psiog.perfinsight.org.AssociateRepository;
import com.psiog.perfinsight.org.Project;
import com.psiog.perfinsight.org.ProjectRepository;
import com.psiog.perfinsight.security.AccessScope;
import com.psiog.perfinsight.security.AccessScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Acceptance criterion 2: manual entries and edits - clearly marked, attributed, never overwriting source rows. */
@Service
@RequiredArgsConstructor
public class ManualDataService {

    private final ActivityRepository activities;
    private final ActivityOverrideRepository overrides;
    private final AssociateRepository associates;
    private final ProjectRepository projects;
    private final AttributionService attribution;
    private final EffectiveActivityService effective;
    private final AccessScopeService access;
    private final AuditService audit;

    @Transactional
    public EffectiveActivity createManual(ActivityDtos.ManualActivityRequest req) {
        AccessScope scope = access.current();
        if (!scope.canEditData() || !scope.canSee(req.associateId()))
            throw new ForbiddenException("Only Managers (for their team), Service Heads and Admins can add manual data");

        Associate who = associates.findById(req.associateId()).orElseThrow(() -> new NotFoundException("Associate", req.associateId()));
        Project project = projects.findById(req.projectId()).orElseThrow(() -> new NotFoundException("Project", req.projectId()));

        Activity a = new Activity();
        a.setSourceTool(ToolType.MANUAL);
        a.setExternalId("manual-" + UUID.randomUUID());
        a.setActivityType(req.type());
        a.setTitle(req.title());
        a.setUrl(req.url());
        a.setAssociate(who);
        a.setSourceProject(project);
        a.setOccurredAt(req.occurredAt());
        a.setOccurredOn(req.occurredAt().atZone(ZoneOffset.UTC).toLocalDate());
        a.setAttributes(req.attributes() == null ? new HashMap<>() : new HashMap<>(req.attributes()));
        a.setOrigin(ActivityOrigin.MANUAL);
        a.setManualReason(req.reason());
        attribution.attribute(a);
        activities.save(a);

        audit.log("MANUAL_ENTRY_CREATED", "Activity", a.getId(),
                Map.of("associateId", who.getId(), "projectId", project.getId(), "type", req.type().name(), "reason", req.reason()));
        return effective.effective(a);
    }

    @Transactional
    public EffectiveActivity addOverride(Long activityId, ActivityDtos.OverrideRequest req) {
        AccessScope scope = access.current();
        Activity a = activities.findById(activityId).orElseThrow(() -> new NotFoundException("Activity", activityId));
        Long owner = a.getAssociate() == null ? null : a.getAssociate().getId();
        if (!scope.canEditData() || (owner != null && !scope.canSee(owner)))
            throw new ForbiddenException("You cannot edit this activity");

        String field = req.field().trim();
        if (!EffectiveActivityService.CORE_FIELDS.contains(field) && !field.startsWith("attributes."))
            throw new BadRequestException("Editable fields: " + EffectiveActivityService.CORE_FIELDS + " or attributes.<name>");
        validateValue(field, req.value());

        EffectiveActivity current = effective.effective(a);
        String original = currentValue(current, field);

        // supersede any previous active edit of the same field (kept for history)
        for (ActivityOverride prev : overrides.findByActivityIdOrderByIdAsc(activityId)) {
            if (prev.isActive() && prev.getField().equals(field)) {
                prev.setActive(false);
                prev.setRevokedAt(Instant.now());
                prev.setRevokedBy(audit.currentActor() + " (superseded)");
            }
        }

        ActivityOverride o = new ActivityOverride();
        o.setActivity(a);
        o.setField(field);
        o.setOriginalValue(original);
        o.setNewValue(req.value());
        o.setReason(req.reason());
        overrides.save(o);

        Map<String, Object> details = new HashMap<>();
        details.put("field", field);
        details.put("from", original);
        details.put("to", req.value());
        details.put("reason", req.reason());
        audit.log("ACTIVITY_EDITED", "Activity", activityId, details);
        return effective.effective(a);
    }

    @Transactional
    public EffectiveActivity revokeOverride(Long overrideId, String reason) {
        ActivityOverride o = overrides.findById(overrideId).orElseThrow(() -> new NotFoundException("Override", overrideId));
        AccessScope scope = access.current();
        Activity a = o.getActivity();
        Long owner = a.getAssociate() == null ? null : a.getAssociate().getId();
        if (!scope.canEditData() || (owner != null && !scope.canSee(owner)))
            throw new ForbiddenException("You cannot edit this activity");
        o.setActive(false);
        o.setRevokedAt(Instant.now());
        o.setRevokedBy(audit.currentActor());
        audit.log("ACTIVITY_EDIT_REVOKED", "Activity", a.getId(),
                Map.of("overrideId", overrideId, "reason", reason == null ? "" : reason));
        return effective.effective(a);
    }

    private void validateValue(String field, String value) {
        switch (field) {
            case "occurredAt" -> {
                if (com.psiog.perfinsight.common.JsonMaps.toInstant(value) == null)
                    throw new BadRequestException("occurredAt must be an ISO date/time");
            }
            case "activityType" -> {
                try { ActivityType.valueOf(value); } catch (Exception e) {
                    throw new BadRequestException("activityType must be one of " + Arrays.toString(ActivityType.values()));
                }
            }
            case "projectId" -> {
                if (value != null && !value.isBlank() && !projects.existsById(Long.valueOf(value)))
                    throw new BadRequestException("Unknown projectId " + value);
            }
            default -> { }
        }
    }

    private String currentValue(EffectiveActivity e, String field) {
        Object v = switch (field) {
            case "title" -> e.title();
            case "occurredAt" -> e.occurredAt();
            case "activityType" -> e.type();
            case "projectId" -> e.projectId();
            case "excluded" -> e.excluded();
            default -> e.attr(field.substring("attributes.".length()));
        };
        return v == null ? null : v.toString();
    }
}
