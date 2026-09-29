package com.psiog.perfinsight.ingest;

import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.ingest.mapping.DefaultMappings;
import com.psiog.perfinsight.org.ProjectRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Criterion 1 + 10: one ADMIN configures each project's tools and field mappings - no code changes. */
@RestController
@RequestMapping("/api/admin/tool-configs")
@RequiredArgsConstructor
public class ToolConfigAdminController {

    private final ProjectToolConfigRepository configs;
    private final ProjectRepository projects;
    private final SyncService sync;
    private final SyncRunRepository runs;
    private final FileImportService imports;
    private final DefaultMappings defaults;
    private final AuditService audit;

    public record ToolConfigRequest(@NotNull Long projectId, @NotNull ToolType toolType, @NotNull ConnectionMode mode,
                                    String name, String baseUrl, String credentialRef, Map<String, Object> settings,
                                    Map<String, Object> fieldMapping, Boolean syncEnabled) {}

    public record ToolConfigDto(Long id, Long projectId, String projectCode, ToolType toolType, ConnectionMode mode,
                                String name, String baseUrl, String credentialRef, Map<String, Object> settings,
                                boolean customMapping, boolean syncEnabled, String cursor, Instant lastSyncAt,
                                String lastSyncStatus, String lastSyncMessage) {
        static ToolConfigDto of(ProjectToolConfig c) {
            return new ToolConfigDto(c.getId(), c.getProject().getId(), c.getProject().getCode(), c.getToolType(),
                    c.getMode(), c.getName(), c.getBaseUrl(), c.getCredentialRef(), c.getSettings(),
                    c.getFieldMapping() != null && !c.getFieldMapping().isEmpty(), c.isSyncEnabled(), c.getCursor(),
                    c.getLastSyncAt(), c.getLastSyncStatus(), c.getLastSyncMessage());
        }
    }

    @GetMapping
    public List<ToolConfigDto> list(@RequestParam(required = false) Long projectId) {
        return (projectId == null ? configs.findAll() : configs.findByProjectId(projectId))
                .stream().map(ToolConfigDto::of).toList();
    }

    @PostMapping
    public ToolConfigDto create(@Valid @RequestBody ToolConfigRequest r) {
        return ToolConfigDto.of(save(new ProjectToolConfig(), r, "TOOL_CONFIG_CREATED"));
    }

    @PutMapping("/{id}")
    public ToolConfigDto update(@PathVariable Long id, @Valid @RequestBody ToolConfigRequest r) {
        ProjectToolConfig c = configs.findById(id).orElseThrow(() -> new NotFoundException("ToolConfig", id));
        return ToolConfigDto.of(save(c, r, "TOOL_CONFIG_UPDATED"));
    }

    /** Effective mapping (custom or default) - start from this when customising a project. */
    @GetMapping("/{id}/mapping")
    public Map<String, Object> mapping(@PathVariable Long id) {
        return defaults.effective(configs.findById(id).orElseThrow(() -> new NotFoundException("ToolConfig", id)));
    }

    @PutMapping("/{id}/mapping")
    public ToolConfigDto putMapping(@PathVariable Long id, @RequestBody Map<String, Object> mapping) {
        ProjectToolConfig c = configs.findById(id).orElseThrow(() -> new NotFoundException("ToolConfig", id));
        c.setFieldMapping(mapping == null || mapping.isEmpty() ? null : mapping);
        configs.save(c);
        audit.log("FIELD_MAPPING_UPDATED", "ProjectToolConfig", id, Map.of("custom", c.getFieldMapping() != null));
        return ToolConfigDto.of(c);
    }

    @GetMapping("/default-mappings/{toolType}")
    public Map<String, Object> defaultMapping(@PathVariable ToolType toolType) {
        Map<String, Object> m = defaults.defaultFor(toolType);
        if (m == null) throw new NotFoundException("Default mapping", toolType);
        return m;
    }

    @PostMapping("/{id}/sync")
    public SyncRun syncNow(@PathVariable Long id) {
        return sync.sync(id, "MANUAL");
    }

    @PostMapping("/sync-all")
    public List<SyncRun> syncAll() {
        return sync.syncAll("MANUAL");
    }

    @PostMapping("/{id}/reset-cursor")
    public void resetCursor(@PathVariable Long id) {
        sync.resetCursor(id);
    }

    @PostMapping(value = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SyncRun importExport(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return imports.importFile(id, file);
    }

    @GetMapping("/runs")
    public List<SyncRun> recentRuns(@RequestParam(required = false) Long configId) {
        return configId == null ? runs.findTop50ByOrderByIdDesc() : runs.findTop20ByToolConfigIdOrderByIdDesc(configId);
    }

    private ProjectToolConfig save(ProjectToolConfig c, ToolConfigRequest r, String action) {
        c.setProject(projects.findById(r.projectId()).orElseThrow(() -> new NotFoundException("Project", r.projectId())));
        c.setToolType(r.toolType());
        c.setMode(r.mode());
        c.setName(r.name());
        c.setBaseUrl(r.baseUrl());
        c.setCredentialRef(r.credentialRef());
        c.setSettings(r.settings() == null ? new java.util.HashMap<>() : r.settings());
        if (r.fieldMapping() != null) c.setFieldMapping(r.fieldMapping().isEmpty() ? null : r.fieldMapping());
        if (r.syncEnabled() != null) c.setSyncEnabled(r.syncEnabled());
        configs.save(c);
        audit.log(action, "ProjectToolConfig", c.getId(), Map.of("toolType", r.toolType().name(), "mode", r.mode().name()));
        return c;
    }
}
