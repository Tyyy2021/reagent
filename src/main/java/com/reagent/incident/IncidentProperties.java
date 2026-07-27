package com.reagent.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "reagent.incidents")
public class IncidentProperties {

    private List<String> trustedSources = new ArrayList<>();

    public List<String> getTrustedSources() {
        return trustedSources;
    }

    public void setTrustedSources(List<String> trustedSources) {
        this.trustedSources = trustedSources;
    }
}
