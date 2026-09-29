package com.psiog.perfinsight.ingest.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import com.psiog.perfinsight.ingest.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git via the GitHub REST API (works for GitHub Enterprise too via baseUrl).
 * settings: owner, repo (required), includeCommits (default true), maxPrPages (default 10).
 * Emits "pull_request" records {pr, reviews[]} and "commit" records.
 */
@Slf4j
@Component
public class GitHubConnector extends AbstractConnector {

    private static final Pattern NEXT = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private final AppProperties props;

    public GitHubConnector(ObjectMapper mapper, RestClient.Builder restBuilder, AppProperties props) {
        super(mapper, restBuilder);
        this.props = props;
    }

    @Override
    public ToolType toolType() {
        return ToolType.GIT;
    }

    @Override
    public FetchResult fetchLive(ProjectToolConfig cfg, String cursor, AppProperties.Credential cred) {
        requireToken(cred, "GitHub");
        String owner = Objects.requireNonNull(cfg.setting("owner"), "settings.owner is required");
        String repo = Objects.requireNonNull(cfg.setting("repo"), "settings.repo is required");
        Instant since = sinceFrom(cursor, props.getSync().getInitialLookbackDays());
        String base = cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank() ? "https://api.github.com" : cfg.getBaseUrl();
        RestClient gh = http().baseUrl(base)
                .defaultHeader("Authorization", "Bearer " + cred.getToken())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();

        List<SourceRecord> out = new ArrayList<>();
        Instant newest = null;

        // Pull requests, newest-updated first; stop once older than the watermark
        String url = "/repos/" + owner + "/" + repo + "/pulls?state=all&sort=updated&direction=desc&per_page=100";
        int maxPages = Integer.parseInt(cfg.setting("maxPrPages", "10"));
        outer:
        for (int page = 0; page < maxPages && url != null; page++) {
            ResponseEntity<JsonNode> resp = page(gh, url);
            JsonNode list = resp.getBody();
            if (list == null || !list.isArray() || list.isEmpty()) break;
            for (JsonNode pr : list) {
                Instant updated = JsonMaps.toInstant(text(pr, "updated_at"));
                if (updated != null && updated.isBefore(since)) break outer;
                int number = pr.path("number").asInt();
                JsonNode detail = gh.get().uri("/repos/{o}/{r}/pulls/{n}", owner, repo, number).retrieve().body(JsonNode.class);
                JsonNode reviews = gh.get().uri("/repos/{o}/{r}/pulls/{n}/reviews?per_page=100", owner, repo, number)
                        .retrieve().body(JsonNode.class);
                ObjectNode wrapper = mapper.createObjectNode();
                wrapper.set("pr", detail == null ? pr : detail);
                wrapper.set("reviews", reviews == null ? mapper.createArrayNode() : reviews);
                wrapper.put("repo", owner + "/" + repo);
                SourceRecord r = prRecord(wrapper);
                out.add(r);
                newest = max(newest, r.updatedAt());
            }
            url = nextLink(resp);
        }

        if (!"false".equalsIgnoreCase(cfg.setting("includeCommits", "true"))) {
            String curl = "/repos/" + owner + "/" + repo + "/commits?per_page=100&since=" + since;
            for (int page = 0; page < 10 && curl != null; page++) {
                ResponseEntity<JsonNode> resp = page(gh, curl);
                JsonNode list = resp.getBody();
                if (list == null || !list.isArray() || list.isEmpty()) break;
                for (JsonNode c : list) {
                    ObjectNode cc = c.deepCopy();
                    cc.put("repo", owner + "/" + repo);
                    SourceRecord r = commitRecord(cc);
                    out.add(r);
                    newest = max(newest, r.updatedAt());
                }
                curl = nextLink(resp);
            }
        }
        log.info("GitHub {}/{}: fetched {} records since {}", owner, repo, out.size(), since);
        return new FetchResult(out, newest == null ? cursor : newest.toString());
    }

    @Override
    public List<SourceRecord> parseDocument(JsonNode doc) {
        List<SourceRecord> out = new ArrayList<>();
        String repo = text(doc, "repo");
        for (JsonNode w : doc.path("pull_requests")) {
            ObjectNode copy = w.deepCopy();
            if (repo != null && !copy.has("repo")) copy.put("repo", repo);
            out.add(prRecord(copy));
        }
        for (JsonNode c : doc.path("commits")) {
            ObjectNode copy = c.deepCopy();
            if (repo != null && !copy.has("repo")) copy.put("repo", repo);
            out.add(commitRecord(copy));
        }
        return out;
    }

    private SourceRecord prRecord(ObjectNode w) {
        JsonNode pr = w.path("pr");
        String author = text(pr.path("user"), "login");
        String mergedBy = text(pr.path("merged_by"), "login");
        Instant created = JsonMaps.toInstant(text(pr, "created_at"));
        int lines = pr.path("additions").asInt(0) + pr.path("deletions").asInt(0);

        ObjectNode d = mapper.createObjectNode();
        d.put("linesChanged", lines);
        d.put("merged", pr.path("merged").asBoolean(text(pr, "merged_at") != null));
        d.put("selfMerged", author != null && author.equals(mergedBy));
        d.put("reviewCount", w.path("reviews").size());
        w.set("_derived", d);

        // enrich each review with timing facts (used for rubber-stamp detection)
        if (w.path("reviews") instanceof ArrayNode reviews) {
            for (JsonNode r : reviews) {
                if (r instanceof ObjectNode ro) {
                    Instant submitted = JsonMaps.toInstant(text(r, "submitted_at"));
                    ObjectNode rd = mapper.createObjectNode();
                    rd.put("prLinesChanged", lines);
                    rd.put("prAuthor", author);
                    rd.put("selfReview", author != null && author.equals(text(r.path("user"), "login")));
                    if (created != null && submitted != null)
                        rd.put("minutesAfterPrOpened", Duration.between(created, submitted).toMinutes());
                    rd.put("bodyLength", Optional.ofNullable(text(r, "body")).orElse("").length());
                    ro.set("_derived", rd);
                }
            }
        }
        Instant updated = JsonMaps.toInstant(text(pr, "updated_at"));
        String id = w.path("repo").asText("repo") + "#" + pr.path("number").asText();
        return new SourceRecord("pull_request", id, updated, toMap(w));
    }

    private SourceRecord commitRecord(ObjectNode c) {
        Instant at = JsonMaps.toInstant(text(c.path("commit").path("author"), "date"));
        return new SourceRecord("commit", text(c, "sha"), at, toMap(c));
    }

    /** Link-header URLs are absolute and already encoded - pass them as a URI so they are not re-encoded. */
    private static ResponseEntity<JsonNode> page(RestClient gh, String url) {
        return url.startsWith("http")
                ? gh.get().uri(java.net.URI.create(url)).retrieve().toEntity(JsonNode.class)
                : gh.get().uri(url).retrieve().toEntity(JsonNode.class);
    }

    private static String nextLink(ResponseEntity<?> resp) {
        String link = resp.getHeaders().getFirst("Link");
        if (link == null) return null;
        Matcher m = NEXT.matcher(link);
        return m.find() ? m.group(1) : null;
    }
}
