package com.psiog.perfinsight.ingest;

import com.psiog.perfinsight.common.BaseEntity;
import com.psiog.perfinsight.org.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * One tool connection for one project (acceptance criterion 1). Everything that differs between
 * projects - mode, URL, keys, repo names, custom fields, field mappings - lives here as data, not code.
 *
 * settings examples:
 *   JIRA:            {"projectKey":"PAY","authType":"BASIC","storyPointsField":"customfield_10016"}
 *   GIT (GitHub):    {"owner":"psiog","repo":"payments-api","includeCommits":true}
 *   AZURE_DEVOPS:    {"organization":"psiog","project":"Claims"}
 *   TEST_MANAGEMENT: {"projectId":"3","flavour":"TESTRAIL"}
 *   SHAREPOINT:      {"siteId":"...","driveId":"..."}
 *   any MOCK:        {"mockFile":"mock/jira/PAY.json"}
 */
@Entity
@Table(name = "project_tool_config")
@Getter @Setter @NoArgsConstructor
public class ProjectToolConfig extends BaseEntity {

    @ManyToOne(optional = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ToolType toolType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConnectionMode mode = ConnectionMode.MOCK;

    private String name;

    private String baseUrl;

    /** Key under app.credentials.* (e.g. "jira", "github"). Secrets never live in the DB. */
    private String credentialRef;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> settings = new HashMap<>();

    /** Optional per-project mapping; null = use classpath default mappings/{tool}.json. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> fieldMapping;

    private boolean syncEnabled = true;

    /** Incremental watermark: ISO instant, or a delta link / continuation token for tools that provide one. */
    @Column(name = "sync_cursor", length = 4000)
    private String cursor;

    private Instant lastSyncAt;
    private String lastSyncStatus;

    @Column(length = 2000)
    private String lastSyncMessage;

    public String setting(String key) {
        Object v = settings == null ? null : settings.get(key);
        return v == null ? null : v.toString();
    }

    public String setting(String key, String def) {
        String v = setting(key);
        return v == null || v.isBlank() ? def : v;
    }
}
