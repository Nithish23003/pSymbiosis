package com.psiog.perfinsight.model;

import com.psiog.perfinsight.org.Persona;
import com.psiog.perfinsight.scoring.measure.MeasureCatalog;
import com.psiog.perfinsight.scoring.measure.MeasureDirection;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class ModelDtos {
    private ModelDtos() {}

    public record MeasureRequest(@NotBlank String measureKey, double weight, Integer minSamples, Boolean enabled, String reasoning) {}

    public record OverrideRequest(@NotBlank String measureKey, Persona persona, Long offeringId, Long projectId,
                                  double weight, String reasoning) {}

    public record ModelRequest(String modelKey, @NotBlank String name, String documentation, String rationale,
                               String changeNote, CohortScope cohortScope, Integer minMeasures, Integer minTools,
                               List<MeasureRequest> measures, List<OverrideRequest> overrides) {}

    public record MeasureDto(String measureKey, String label, String category, MeasureDirection direction,
                             boolean rateBased, double weight, int minSamples, boolean enabled, String reasoning) {}

    public record OverrideDto(Long id, String measureKey, Persona persona, Long offeringId, Long projectId,
                              double weight, String reasoning) {}

    public record ModelDto(Long id, String modelKey, int version, String name, ModelStatus status, String documentation,
                           String rationale, String changeNote, CohortScope cohortScope, int minMeasures, int minTools,
                           Instant publishedAt, String publishedBy, String createdBy, Instant createdAt,
                           List<MeasureDto> measures, List<OverrideDto> overrides) {

        public static ModelDto of(PerformanceModel m, MeasureCatalog catalog) {
            List<MeasureDto> ms = m.getMeasures().stream().map(mm -> {
                var def = catalog.get(mm.getMeasureKey());
                return new MeasureDto(mm.getMeasureKey(),
                        def.map(d -> d.label()).orElse(mm.getMeasureKey()),
                        def.map(d -> d.category()).orElse(null),
                        def.map(d -> d.direction()).orElse(null),
                        def.map(d -> d.rateBased()).orElse(false),
                        mm.getWeight(), mm.getMinSamples(), mm.isEnabled(), mm.getReasoning());
            }).toList();
            List<OverrideDto> os = m.getOverrides().stream().map(o -> new OverrideDto(o.getId(), o.getMeasureKey(),
                    o.getPersona(), o.getOfferingId(), o.getProjectId(), o.getWeight(), o.getReasoning())).toList();
            return new ModelDto(m.getId(), m.getModelKey(), m.getVersion(), m.getName(), m.getStatus(), m.getDocumentation(),
                    m.getRationale(), m.getChangeNote(), m.getCohortScope(), m.getMinMeasures(), m.getMinTools(),
                    m.getPublishedAt(), m.getPublishedBy(), m.getCreatedBy(), m.getCreatedAt(), ms, os);
        }
    }
}
