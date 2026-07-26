package com.reagent.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reagent.llm")
public class DemoLlmProperties {

    private String mode = "openai";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean scripted() {
        return "scripted".equals(mode);
    }
}
