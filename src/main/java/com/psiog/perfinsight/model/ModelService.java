package com.psiog.perfinsight.model;

import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.BadRequestException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.model.ModelDtos.*;
import com.psiog.perfinsight.org.Persona;
import com.psiog.perfinsight.scoring.measure.MeasureCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ModelService {

    private final PerformanceModelRepository models;
    private final MeasureCatalog catalog;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<ModelDto> list() {
        return models.findAllByOrderByModelKeyAscVersionDesc().stream().map(m -> ModelDto.of(m, catalog)).toList();
    }

    @Transactional(readOnly = true)
    public ModelDto get(Long id) {
        return ModelDto.of(find(id), catalog);
    }

    /** The one model currently used for new scoring runs (latest published). */
    @Transactional(readOnly = true)
    public PerformanceModel active() {
        PerformanceModel m = models.findTopByStatusOrderByPublishedAtDesc(ModelStatus.PUBLISHED)
                .orElseThrow(() -> new BadRequestException("No published performance model - create and publish one first"));
        m.getMeasures().size();
        m.getOverrides().size();
        return m;
    }

    @Transactional
    public ModelDto createDraft(ModelRequest r) {
        String key = r.modelKey() == null || r.modelKey().isBlank() ? "default" : r.modelKey();
        int version = models.findTopByModelKeyOrderByVersionDesc(key).map(PerformanceModel::getVersion).orElse(0) + 1;
        PerformanceModel m = new PerformanceModel();
        m.setModelKey(key);
        m.setVersion(version);
        apply(m, r);
        models.save(m);
        audit.log("MODEL_DRAFT_CREATED", "PerformanceModel", m.getId(), Map.of("key", key, "version", version));
        return ModelDto.of(m, catalog);
    }

    @Transactional
    public ModelDto updateDraft(Long id, ModelRequest r) {
        PerformanceModel m = find(id);
        if (m.getStatus() != ModelStatus.DRAFT)
            throw new BadRequestException("Published models are immutable - create a new version instead");
        apply(m, r);
        audit.log("MODEL_DRAFT_UPDATED", "PerformanceModel", id, Map.of());
        return ModelDto.of(m, catalog);
    }

    @Transactional
    public ModelDto publish(Long id) {
        PerformanceModel m = find(id);
        if (m.getStatus() != ModelStatus.DRAFT) throw new BadRequestException("Only drafts can be published");
        validateForPublish(m);
        for (PerformanceModel other : models.findAll()) {
            if (other.getStatus() == ModelStatus.PUBLISHED) other.setStatus(ModelStatus.SUPERSEDED);
        }
        m.setStatus(ModelStatus.PUBLISHED);
        m.setPublishedAt(Instant.now());
        m.setPublishedBy(audit.currentActor());
        audit.log("MODEL_PUBLISHED", "PerformanceModel", id, Map.of("key", m.getModelKey(), "version", m.getVersion()));
        return ModelDto.of(m, catalog);
    }

    /** Copy any version into a new editable draft (v+1). */
    @Transactional
    public ModelDto newVersion(Long id, String changeNote) {
        PerformanceModel src = find(id);
        int version = models.findTopByModelKeyOrderByVersionDesc(src.getModelKey()).map(PerformanceModel::getVersion).orElse(0) + 1;
        PerformanceModel m = new PerformanceModel();
        m.setModelKey(src.getModelKey());
        m.setVersion(version);
        m.setName(src.getName());
        m.setDocumentation(src.getDocumentation());
        m.setRationale(src.getRationale());
        m.setChangeNote(changeNote);
        m.setCohortScope(src.getCohortScope());
        m.setMinMeasures(src.getMinMeasures());
        m.setMinTools(src.getMinTools());
        for (ModelMeasure mm : src.getMeasures()) {
            ModelMeasure c = new ModelMeasure();
            c.setModel(m); c.setMeasureKey(mm.getMeasureKey()); c.setWeight(mm.getWeight());
            c.setMinSamples(mm.getMinSamples()); c.setEnabled(mm.isEnabled()); c.setReasoning(mm.getReasoning());
            m.getMeasures().add(c);
        }
        for (WeightOverride o : src.getOverrides()) {
            WeightOverride c = new WeightOverride();
            c.setModel(m); c.setMeasureKey(o.getMeasureKey()); c.setPersona(o.getPersona());
            c.setOfferingId(o.getOfferingId()); c.setProjectId(o.getProjectId()); c.setWeight(o.getWeight());
            c.setReasoning(o.getReasoning());
            m.getOverrides().add(c);
        }
        models.save(m);
        audit.log("MODEL_VERSION_CREATED", "PerformanceModel", m.getId(), Map.of("from", id, "version", version));
        return ModelDto.of(m, catalog);
    }

    /** Weights for one scope: base weights, then matching overrides from least to most specific. */
    public Map<String, Double> resolveWeights(PerformanceModel m, Persona persona, Long offeringId, Long projectId) {
        Map<String, Double> w = new LinkedHashMap<>();
        for (ModelMeasure mm : m.getMeasures()) if (mm.isEnabled()) w.put(mm.getMeasureKey(), mm.getWeight());
        m.getOverrides().stream()
                .filter(o -> o.matches(persona, offeringId, projectId))
                .sorted(Comparator.comparingInt(WeightOverride::specificity))
                .forEach(o -> { if (w.containsKey(o.getMeasureKey())) w.put(o.getMeasureKey(), o.getWeight()); });
        w.values().removeIf(v -> v == null || v <= 0);
        return w;
    }

    public PerformanceModel find(Long id) {
        return models.findById(id).orElseThrow(() -> new NotFoundException("PerformanceModel", id));
    }

    private void apply(PerformanceModel m, ModelRequest r) {
        m.setName(r.name());
        m.setDocumentation(r.documentation());
        m.setRationale(r.rationale());
        m.setChangeNote(r.changeNote());
        if (r.cohortScope() != null) m.setCohortScope(r.cohortScope());
        if (r.minMeasures() != null) m.setMinMeasures(r.minMeasures());
        if (r.minTools() != null) m.setMinTools(r.minTools());
        if (r.measures() != null) {
            m.getMeasures().clear();
            for (MeasureRequest mr : r.measures()) {
                if (catalog.get(mr.measureKey()).isEmpty()) throw new BadRequestException("Unknown measure " + mr.measureKey());
                if (mr.weight() < 0) throw new BadRequestException("Weights must be >= 0");
                ModelMeasure mm = new ModelMeasure();
                mm.setModel(m);
                mm.setMeasureKey(mr.measureKey());
                mm.setWeight(mr.weight());
                mm.setMinSamples(mr.minSamples() == null ? 3 : mr.minSamples());
                mm.setEnabled(mr.enabled() == null || mr.enabled());
                mm.setReasoning(mr.reasoning());
                m.getMeasures().add(mm);
            }
        }
        if (r.overrides() != null) {
            m.getOverrides().clear();
            for (OverrideRequest or : r.overrides()) {
                if (catalog.get(or.measureKey()).isEmpty()) throw new BadRequestException("Unknown measure " + or.measureKey());
                WeightOverride o = new WeightOverride();
                o.setModel(m);
                o.setMeasureKey(or.measureKey());
                o.setPersona(or.persona());
                o.setOfferingId(or.offeringId());
                o.setProjectId(or.projectId());
                o.setWeight(or.weight());
                o.setReasoning(or.reasoning());
                m.getOverrides().add(o);
            }
        }
    }

    private void validateForPublish(PerformanceModel m) {
        if (m.getDocumentation() == null || m.getDocumentation().isBlank())
            throw new BadRequestException("Document the model (what good/poor looks like) before publishing");
        if (m.getRationale() == null || m.getRationale().isBlank())
            throw new BadRequestException("Record the reasoning behind the measures and weights before publishing");
        long usable = m.getMeasures().stream().filter(mm -> mm.isEnabled() && mm.getWeight() > 0).count();
        if (usable < Math.max(3, m.getMinMeasures()))
            throw new BadRequestException("A model needs at least " + Math.max(3, m.getMinMeasures())
                    + " weighted measures - never a single activity count");
        if (m.getMinMeasures() < 2) throw new BadRequestException("minMeasures must be at least 2");
    }
}
