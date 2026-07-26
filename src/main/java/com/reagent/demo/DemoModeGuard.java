package com.reagent.demo;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

@Component
public final class DemoModeGuard implements InitializingBean {

    private static final Set<String> SCRIPTED_PROFILES =
            Set.of("demo-smoke", "demo-chaos");

    private final DemoLlmProperties properties;
    private final Environment environment;

    public DemoModeGuard(DemoLlmProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!Set.of("openai", "scripted").contains(properties.getMode())) {
            throw new IllegalStateException("reagent.llm.mode must be openai or scripted");
        }
        if (properties.scripted()
                && Arrays.stream(environment.getActiveProfiles())
                .noneMatch(SCRIPTED_PROFILES::contains)) {
            throw new IllegalStateException(
                    "scripted LLM mode requires demo-smoke or demo-chaos profile");
        }
    }
}
