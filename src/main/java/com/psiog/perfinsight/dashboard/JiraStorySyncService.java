package com.psiog.perfinsight.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.psiog.perfinsight.common.BadRequestException;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.jira.JiraApiClient;
import com.psiog.perfinsight.jira.JiraUserStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Pulls every issue of the Jira project and upserts it into jira_story (matched to users by email / name). */
@Service
@RequiredArgsConstructor
public class JiraStorySyncService {

    private final JiraApiClient jira;
    private final JiraUserStatsService jiraStats;
    private final DashUserRepository users;
    private final JiraStoryRepository stories;
    private final AppProperties props;

    @Transactional
    public Map<String, Object> sync(String projectKeyParam) {
        String key = projectKeyParam == null || projectKeyParam.isBlank() ? props.getJira().getDefaultProjectKey() : projectKeyParam;
        if (key == null || key.isBlank()) throw new BadRequestException("projectKey is required (or set JIRA_PROJECT_KEY)");

        List<String> spFields = jiraStats.storyPointFields();
        List<String> fields = new ArrayList<>(List.of("summary", "status", "assignee", "created", "resolutiondate"));
        fields.addAll(spFields);
        List<JsonNode> issues = jira.searchIssues("project = \"" + key + "\" ORDER BY created ASC", fields,
                props.getJira().getMaxIssues());

        List<DashUser> all = users.findAll();
        int created = 0, updated = 0;
        List<String> unassigned = new ArrayList<>(), unmatched = new ArrayList<>();

        for (JsonNode issue : issues) {
            String storyKey = issue.path("key").asText();
            JsonNode f = issue.path("fields");
            JsonNode a = f.path("assignee");
            DashUser dev = null;
            if (a.isMissingNode() || a.isNull()) unassigned.add(storyKey);
            else {
                dev = match(a, all);
                if (dev == null) unmatched.add(storyKey + " -> " + a.path("displayName").asText("?"));
            }

            JiraStory s = stories.findByStoryId(storyKey).orElseGet(JiraStory::new);
            boolean isNew = s.getId() == null;
            s.setStoryId(storyKey);
            s.setStoryTitle(f.path("summary").asText(null));
            Double sp = null;
            for (String fld : spFields) if (f.path(fld).isNumber()) { sp = f.path(fld).asDouble(); break; }
            s.setStoryPoints(sp == null ? null : (int) Math.round(sp));
            String cat = f.path("status").path("statusCategory").path("key").asText("");
            s.setStatus(switch (cat) { case "done" -> "DONE"; case "indeterminate" -> "IN_PROGRESS"; default -> "TO_DO"; });
            if (dev != null || isNew) s.setDeveloper(dev);   // keep a manual mapping if Jira has no match
            s.setCreatedDate(date(f.path("created").asText(null)));
            s.setCompletedDate("done".equals(cat) ? date(f.path("resolutiondate").asText(null)) : null);
            stories.save(s);
            if (isNew) created++; else updated++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectKey", key);
        out.put("issuesRead", issues.size());
        out.put("created", created);
        out.put("updated", updated);
        out.put("storyPointFields", spFields);
        out.put("unassignedIssues", unassigned);
        out.put("assigneesNotInUsersTable", unmatched);
        return out;
    }

    /** email -> exact name -> first name. */
    private DashUser match(JsonNode assignee, List<DashUser> all) {
        String email = assignee.path("emailAddress").asText("").toLowerCase(Locale.ROOT);
        String display = assignee.path("displayName").asText("").trim().toLowerCase(Locale.ROOT);
        String first = display.contains(" ") ? display.substring(0, display.indexOf(' ')) : display;
        for (DashUser u : all) if (!email.isEmpty() && email.equalsIgnoreCase(u.getEmail())) return u;
        for (DashUser u : all) if (!display.isEmpty() && display.equalsIgnoreCase(u.getName())) return u;
        for (DashUser u : all) {
            String n = u.getName() == null ? "" : u.getName().toLowerCase(Locale.ROOT);
            String uFirst = n.contains(" ") ? n.substring(0, n.indexOf(' ')) : n;
            if (!first.isEmpty() && first.equals(uFirst)) return u;
        }
        return null;
    }

    private static LocalDate date(String v) {
        Instant i = JsonMaps.toInstant(v);
        return i == null ? null : i.atZone(ZoneOffset.UTC).toLocalDate();
    }
}