package com.psiog.perfinsight.ingest.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.psiog.perfinsight.activity.ActivityType;
import com.psiog.perfinsight.ingest.connector.SourceRecord;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MappingEngineTest {

    private final ObjectMapper om = new ObjectMapper();
    private final MappingEngine engine = new MappingEngine(Configuration.builder()
            .jsonProvider(new JacksonJsonProvider(om)).mappingProvider(new JacksonMappingProvider(om))
            .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL).build());

    private Map<String, Object> load(String path) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            return om.readValue(in, new TypeReference<>() {});
        }
    }

    @Test
    void mapsGithubPrAndReviews() throws Exception {
        Map<String, Object> spec = load("mappings/git.json");
        Map<String, Object> payload = om.readValue("""
            {"repo":"psiog/x","pr":{"number":7,"title":"PAY-1: fix","html_url":"u","user":{"login":"alice"},
             "created_at":"2026-06-01T10:00:00Z","merged_at":"2026-06-02T10:00:00Z","merged":true,
             "merged_by":{"login":"bob"},"additions":10,"deletions":5,"head":{"ref":"feature/PAY-1"}},
             "_derived":{"merged":true,"selfMerged":false,"linesChanged":15,"reviewCount":2},
             "reviews":[
               {"id":1,"user":{"login":"bob"},"state":"APPROVED","submitted_at":"2026-06-01T12:00:00Z","_derived":{"selfReview":false}},
               {"id":2,"user":{"login":"alice"},"state":"COMMENTED","submitted_at":"2026-06-01T13:00:00Z","_derived":{"selfReview":true}}]}
            """, new TypeReference<>() {});
        List<NormalizedActivity> out = engine.map(spec, new SourceRecord("pull_request", "psiog/x#7", Instant.now(), payload), Map.of());
        assertThat(out).extracting(NormalizedActivity::type).containsExactly(ActivityType.PULL_REQUEST, ActivityType.CODE_REVIEW);
        assertThat(out.get(0).externalId()).isEqualTo("gh-pr-psiog/x#7");
        assertThat(out.get(0).attributes().get("linesChanged")).isEqualTo(15.0);
        assertThat(out.get(1).actor().username()).isEqualTo("bob");
        assertThat(out.get(1).attributes().get("prRef")).isEqualTo("psiog/x#7");
    }

    @Test
    void mapsJiraCsvRowWithSettingsPlaceholder() throws Exception {
        Map<String, Object> spec = load("mappings/jira.json");
        Map<String, Object> row = Map.of("Issue key", "INS-9", "Summary", "Do it", "Assignee", "Aditya Rao",
                "Assignee Id", "abc", "Status", "Done", "Status Category", "Done",
                "Resolved", "02/Jun/26 05:02 PM", "Created", "27/May/26 03:35 PM", "Custom field (Story Points)", "3");
        List<NormalizedActivity> out = engine.map(spec, new SourceRecord("row", "INS-9", Instant.now(), row),
                Map.of("browseUrl", "https://jira.example"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).url()).isEqualTo("https://jira.example/browse/INS-9");
        assertThat(out.get(0).attributes().get("completed")).isEqualTo(true);
        assertThat(out.get(0).attributes().get("storyPoints")).isEqualTo(3.0);
        assertThat(out.get(0).attributes().get("cycleTimeHours")).isNotNull();
    }
}
