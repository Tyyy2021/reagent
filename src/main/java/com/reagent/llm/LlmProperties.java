package com.reagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 接入配置,对应 application.yml 里的 reagent.llm.*
 */
@ConfigurationProperties(prefix = "reagent.llm")
public class LlmProperties {

    /** OpenAI 兼容服务的 base url,如 http://localhost:11434/v1 或 https://api.openai.com/v1 */
    private String baseUrl;

    /** API key(Ollama 可填任意占位值) */
    private String apiKey;

    /** 模型名,如 qwen2.5 / gpt-4o-mini */
    private String model;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
