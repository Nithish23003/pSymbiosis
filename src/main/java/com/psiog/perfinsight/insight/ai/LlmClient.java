package com.psiog.perfinsight.insight.ai;

public interface LlmClient {
    boolean enabled();
    String providerName();
    String complete(String systemPrompt, String userPrompt);
}
