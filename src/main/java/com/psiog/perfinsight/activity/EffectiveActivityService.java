package com.psiog.perfinsight.activity;

import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.org.Project;
import com.psiog.perfinsight.org.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EffectiveActivityService {

    public static final Set<String> CORE_FIELDS = Set.of("title", "occurredAt", "activityType", "projectId", "excluded");

    private final ActivityOverrideRepository overrides;
    private final ProjectRepository projects;

    @Transactional(readOnly = true)
    public List<EffectiveActivity> effective(List<Activity> activities) {
        if (activities.isEmpty()) return List.of();
        List<Long> ids = activities.stream().map(Activity::getId).toList();
        Map<Long, List<ActivityOverride>> byActivity = new HashMap<>();
        // chunk to keep IN-lists reasonable
        for (int i = 0; i < ids.size(); i += 1000) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + 1000));
            for (ActivityOverride o : overrides.findByActivityIdInAndActiveTrueOrderByIdAsc(chunk)) {
                byActivity.computeIfAbsent(o.getActivity().getId(), k -> new ArrayList<>()).add(o);
            }
        }
        Map<Long, String> projectCodes = new HashMap<>();
        return activities.stream()
                .map(a -> build(a, byActivity.getOrDefault(a.getId(), List.of()), projectCodes))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EffectiveActivity effective(Activity a) {
        return effective(List.of(a)).get(0);
    }

    private EffectiveActivity build(Activity a, List<ActivityOverride> ovs, Map<Long, String> projectCodes) {
        String title = a.getTitle();
        Instant occurredAt = a.getOccurredAt();
        ActivityType type = a.getActivityType();
        Long projectId = a.getProject() == null ? null : a.getProject().getId();
        boolean excluded = false;
        Map<String, Object> attrs = new LinkedHashMap<>(a.getAttributes() == null ? Map.of() : a.getAttributes());

        for (ActivityOverride o : ovs) {
            String f = o.getField();
            String v = o.getNewValue();
            switch (f) {
                case "title" -> title = v;
                case "occurredAt" -> { Instant i = JsonMaps.toInstant(v); if (i != null) occurredAt = i; }
                case "activityType" -> type = ActivityType.valueOf(v);
                case "projectId" -> projectId = v == null || v.isBlank() ? null : Long.valueOf(v);
                case "excluded" -> excluded = Boolean.parseBoolean(v);
                default -> {
                    if (f.startsWith("attributes.")) attrs.put(f.substring("attributes.".length()), typed(v));
                }
            }
        }
        final Long pid = projectId;
        String projectCode = pid == null ? null : projectCodes.computeIfAbsent(pid,
                id -> projects.findById(id).map(Project::getCode).orElse(null));
        LocalDate occurredOn = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();

        return new EffectiveActivity(
                a.getId(), a.getSourceTool(), a.getExternalId(), type, title, a.getUrl(),
                a.getAssociate() == null ? null : a.getAssociate().getId(),
                a.getAssociate() == null ? null : a.getAssociate().getFullName(),
                pid, projectCode,
                a.getSourceProject() == null ? null : a.getSourceProject().getId(),
                a.getPersona(), a.getAttributionStatus(), occurredAt, occurredOn, attrs, a.getOrigin(), excluded,
                !ovs.isEmpty(), ovs.stream().map(EffectiveActivity.OverrideDto::of).toList(),
                a.getToolAccount() == null ? null : a.getToolAccount().getId(),
                a.getToolAccount() == null ? null : a.getToolAccount().label(),
                a.getManualReason(), a.getCreatedBy(), a.getCreatedAt());
    }

    static Object typed(String v) {
        if (v == null) return null;
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return Boolean.parseBoolean(v);
        Double d = JsonMaps.toDouble(v);
        return d != null ? d : v;
    }
}
