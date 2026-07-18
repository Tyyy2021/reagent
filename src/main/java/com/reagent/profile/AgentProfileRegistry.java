package com.reagent.profile;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves trusted configuration to a new frozen task snapshot. */
@Component
public class AgentProfileRegistry {

    private final String defaultId;
    private final Map<String, AgentProfileDefinition> definitions;
    private final ToolCatalogResolver catalogResolver;

    public AgentProfileRegistry(AgentProfileProperties properties, ToolCatalogResolver catalogResolver) {
        this.defaultId = requireId(properties.getDefaultId(), "default profile");
        Map<String, AgentProfileDefinition> configured = new LinkedHashMap<>();
        properties.getDefinitions().forEach((id, profile) -> configured.put(
                requireId(id, "profile ID"),
                new AgentProfileDefinition(
                        id,
                        profile.getVersion(),
                        profile.getSystemPrompt(),
                        profile.getKnowledgeBaseId(),
                        profile.getKnowledgeIndexVersion(),
                        profile.getMcpServerIds(),
                        profile.getToolNames())));
        this.definitions = Map.copyOf(configured);
        this.catalogResolver = catalogResolver;
    }

    public TaskProfileSnapshot snapshot(String profileId) {
        String selected = profileId == null || profileId.isBlank()
                ? defaultId
                : profileId.trim();
        AgentProfileDefinition definition = definitions.get(selected);
        if (definition == null) {
            throw new UnknownProfileException(selected);
        }
        return catalogResolver.snapshot(definition);
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
