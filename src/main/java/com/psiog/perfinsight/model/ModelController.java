package com.psiog.perfinsight.model;

import com.psiog.perfinsight.model.ModelDtos.*;
import com.psiog.perfinsight.scoring.measure.MeasureCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everyone can read the model and its documentation (transparency); only ADMIN changes it. */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService models;
    private final MeasureCatalog catalog;

    @GetMapping
    public List<ModelDto> list() { return models.list(); }

    @GetMapping("/{id}")
    public ModelDto get(@PathVariable Long id) { return models.get(id); }

    @GetMapping("/active")
    public ModelDto active() { return models.get(models.active().getId()); }

    @GetMapping("/measures")
    public List<Map<String, Object>> measureCatalog() {
        return catalog.all().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.key());
            m.put("label", d.label());
            m.put("category", d.category());
            m.put("description", d.description());
            m.put("direction", d.direction());
            m.put("rateBased", d.rateBased());
            m.put("unit", d.unit());
            return m;
        }).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ModelDto create(@Valid @RequestBody ModelRequest r) { return models.createDraft(r); }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ModelDto update(@PathVariable Long id, @Valid @RequestBody ModelRequest r) { return models.updateDraft(id, r); }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ModelDto publish(@PathVariable Long id) { return models.publish(id); }

    @PostMapping("/{id}/new-version")
    @PreAuthorize("hasRole('ADMIN')")
    public ModelDto newVersion(@PathVariable Long id, @RequestParam(required = false) String changeNote) {
        return models.newVersion(id, changeNote);
    }
}
