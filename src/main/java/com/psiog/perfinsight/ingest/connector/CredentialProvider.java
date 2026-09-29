package com.psiog.perfinsight.ingest.connector;

import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.ingest.ProjectToolConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves credentialRef -> app.credentials.&lt;ref&gt; (populated from environment variables). */
@Component
@RequiredArgsConstructor
public class CredentialProvider {
    private final AppProperties props;

    public AppProperties.Credential forConfig(ProjectToolConfig cfg) {
        String ref = cfg.getCredentialRef();
        if (ref == null || ref.isBlank()) ref = defaultRef(cfg);
        return props.getCredentials().get(ref);
    }

    private String defaultRef(ProjectToolConfig cfg) {
        return switch (cfg.getToolType()) {
            case JIRA -> "jira";
            case GIT -> "github";
            case AZURE_DEVOPS -> "ado";
            case TEST_MANAGEMENT -> "testrail";
            case SHAREPOINT -> "sharepoint";
            case MANUAL -> "manual";
        };
    }
}
