package com.psiog.perfinsight.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Security security = new Security();
    private Map<String, Credential> credentials = new HashMap<>();
    private Sync sync = new Sync();
    private Scoring scoring = new Scoring();
    private Ai ai = new Ai();
    private Demo demo = new Demo();
    private Jira jira = new Jira();

    @Getter @Setter
    public static class Security {
        private boolean openAccess = false;
        private boolean devAuth = false;
        private Map<String, String> roleMapping = new HashMap<>();
        private List<String> identityClaims = new ArrayList<>(List.of("preferred_username", "email", "upn"));
        private List<String> corsAllowedOrigins = new ArrayList<>();
    }

    @Getter @Setter
    public static class Credential {
        private String username;
        private String token;
        private String tenantId;
        private String clientId;
    }

    @Getter @Setter
    public static class Sync {
        private boolean enabled = true;
        private String cron = "0 0 */6 * * *";
        private int initialLookbackDays = 180;
    }

    @Getter @Setter
    public static class Scoring {
        private String snapshotCron = "0 30 1 1 * *";
        private int workingDaysPerNormalisedPeriod = 20;
        private int minActiveDays = 5;
        private int minCohortSize = 3;
        private double maxSingleMeasureWeight = 0.40;
    }

    @Getter @Setter
    public static class Ai {
        private String provider = "none";
        private String apiKey;
        private String model = "gpt-4o-mini";
        private String baseUrl = "https://api.openai.com/v1";
        private String azureEndpoint;
        private String azureDeployment;
        private String azureApiVersion = "2024-10-21";
    }

    @Getter @Setter
    public static class Demo {
        private boolean seed = false;
        private LocalDate periodStart = LocalDate.of(2026, 6, 1);
        private LocalDate periodEnd = LocalDate.of(2026, 9, 30);
    }

    /** Direct, live Jira Cloud lookups (package com.psiog.perfinsight.jira). Credentials: app.credentials.jira */
    @Getter @Setter
    public static class Jira {
        /** e.g. https://psiog.atlassian.net */
        private String baseUrl;
        /** used when the request does not pass projectKey */
        private String defaultProjectKey;
        /** comma list of custom field ids; blank = auto-detect "Story Points" / "Story point estimate" */
        private String storyPointsFields;
        /** read commits / PRs from Jira's Development panel (needs GitHub/Bitbucket/GitLab for Jira app) */
        private boolean includeDevInfo = true;
        private int maxIssues = 1000;
        private int maxDevLookups = 200;
    }
}
