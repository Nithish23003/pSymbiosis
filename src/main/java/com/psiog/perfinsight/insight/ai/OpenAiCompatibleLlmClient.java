package com.psiog.perfinsight.insight.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.psiog.perfinsight.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Chat-completions client for OpenAI or Azure OpenAI (app.ai.provider = openai | azure-openai).
 * provider = none -> the deterministic template summariser is used instead.
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final AppProperties props;
    private final RestClient.Builder restBuilder;

    @Override
    public boolean enabled() {
        AppProperties.Ai ai = props.getAi();
        return ai.getApiKey() != null && !ai.getApiKey().isBlank()
                && ("openai".equalsIgnoreCase(ai.getProvider()) || "azure-openai".equalsIgnoreCase(ai.getProvider()));
    }

    @Override
    public String providerName() {
        return props.getAi().getProvider() + ":" + ("azure-openai".equalsIgnoreCase(props.getAi().getProvider())
                ? props.getAi().getAzureDeployment() : props.getAi().getModel());
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        AppProperties.Ai ai = props.getAi();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("temperature", 0.2);

        RestClient.RequestBodySpec req;
        if ("azure-openai".equalsIgnoreCase(ai.getProvider())) {
            String url = ai.getAzureEndpoint().replaceAll("/+$", "") + "/openai/deployments/" + ai.getAzureDeployment()
                    + "/chat/completions?api-version=" + ai.getAzureApiVersion();
            req = restBuilder.clone().build().post().uri(java.net.URI.create(url)).header("api-key", ai.getApiKey());
        } else {
            body.put("model", ai.getModel());
            req = restBuilder.clone().build().post().uri(java.net.URI.create(ai.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .header("Authorization", "Bearer " + ai.getApiKey());
        }
        JsonNode res = req.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        if (res == null) throw new IllegalStateException("Empty LLM response");
        return res.path("choices").path(0).path("message").path("content").asText();
    }
}
