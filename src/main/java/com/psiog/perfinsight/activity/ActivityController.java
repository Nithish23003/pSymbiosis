package com.psiog.perfinsight.activity;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityQueryService query;
    private final ManualDataService manual;

    @GetMapping
    public List<EffectiveActivity> search(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) Long associateId,
                                          @RequestParam(required = false) Long projectId,
                                          @RequestParam(required = false) ActivityType type,
                                          @RequestParam(defaultValue = "500") int limit) {
        return query.search(from, to, associateId, projectId, type, limit);
    }

    @GetMapping("/{id}")
    public EffectiveActivity get(@PathVariable Long id) {
        return query.get(id);
    }

    /** Add data for tools that can't be integrated. Marked origin=MANUAL with who/when/why. */
    @PostMapping("/manual")
    public EffectiveActivity createManual(@Valid @RequestBody ActivityDtos.ManualActivityRequest req) {
        return manual.createManual(req);
    }

    /** Edit a field on top of source data; the source value is preserved and shown alongside. */
    @PostMapping("/{id}/overrides")
    public EffectiveActivity override(@PathVariable Long id, @Valid @RequestBody ActivityDtos.OverrideRequest req) {
        return manual.addOverride(id, req);
    }

    @PostMapping("/overrides/{overrideId}/revoke")
    public EffectiveActivity revoke(@PathVariable Long overrideId, @RequestBody(required = false) ActivityDtos.RevokeRequest req) {
        return manual.revokeOverride(overrideId, req == null ? null : req.reason());
    }
}
