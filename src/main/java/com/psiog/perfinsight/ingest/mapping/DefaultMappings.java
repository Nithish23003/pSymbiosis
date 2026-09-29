package com.psiog.perfinsight.ingest.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Loads classpath mappings/{tool}.json. A project's own fieldMapping (if set) replaces the default entirely. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMappings {

    private final ObjectMapper mapper;
    private final Map<ToolType, Map<String, Object>> defaults = new EnumMap<>(ToolType.class);

    @PostConstruct
    void load() {
        for (ToolType t : ToolType.values()) {
            ClassPathResource r = new ClassPathResource("mappings/" + t.name().toLowerCase(Locale.ROOT) + ".json");
            if (!r.exists()) continue;
            try (InputStream in = r.getInputStream()) {
                defaults.put(t, mapper.readValue(in, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                throw new IllegalStateException("Invalid default mapping for " + t + ": " + e.getMessage(), e);
            }
        }
        log.info("Loaded default field mappings for {}", defaults.keySet());
    }

    public Map<String, Object> defaultFor(ToolType t) {
        return defaults.get(t);
    }

    public Map<String, Object> effective(ProjectToolConfig cfg) {
        if (cfg.getFieldMapping() != null && !cfg.getFieldMapping().isEmpty()) return cfg.getFieldMapping();
        Map<String, Object> d = defaults.get(cfg.getToolType());
        if (d == null) throw new IllegalStateException("No field mapping for " + cfg.getToolType());
        return d;
    }
}
