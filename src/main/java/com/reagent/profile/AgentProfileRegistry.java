package com.reagent.profile;

import com.reagent.rag.KnowledgeVersionProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves trusted configuration to a new frozen task snapshot. */
@Component
public class AgentProfileRegistry {

    private final String defaultId;
    private final Map<String, AgentProfileDefinition> definitions;
    private final ToolCatalogResolver catalogResolver;
    private final KnowledgeVersionProvider knowledgeVersionProvider;

    @Autowired
    public AgentProfileRegistry(AgentProfileProperties properties, ToolCatalogResolver catalogResolver,
                                KnowledgeVersionProvider knowledgeVersionProvider) {
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
        this.knowledgeVersionProvider = knowledgeVersionProvider;
    }

    /** Compatibility constructor for coding-only unit fixtures. */
    public AgentProfileRegistry(AgentProfileProperties properties, ToolCatalogResolver catalogResolver) {
        this(properties, catalogResolver, knowledgeBaseId -> {
            throw new IllegalStateException("active knowledge version provider is unavailable");
        });
    }

    public TaskProfileSnapshot snapshot(String profileId) {
        String selected = profileId == null || profileId.isBlank()
                ? defaultId
                : profileId.trim();
        AgentProfileDefinition definition = definitions.get(selected);
        if (definition == null) {
            throw new UnknownProfileException(selected);
        }
        return catalogResolver.snapshot(freezeActiveVersion(definition));
    }

    private AgentProfileDefinition freezeActiveVersion(AgentProfileDefinition definition) {
        if (definition.knowledgeBaseId() == null
                || definition.knowledgeBaseId().isBlank()
                || !"active".equals(definition.knowledgeIndexVersion())) {
            return definition;
        }
        String concreteVersion = knowledgeVersionProvider.requireActiveVersion(definition.knowledgeBaseId());
        if (concreteVersion == null || concreteVersion.isBlank() || "active".equals(concreteVersion)) {
            throw new IllegalStateException("active knowledge version is unavailable");
        }
        return new AgentProfileDefinition(
                definition.profileId(),
                definition.profileVersion(),
                definition.systemPrompt(),
                definition.knowledgeBaseId(),
                concreteVersion,
                definition.mcpServerIds(),
                definition.toolNames());
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
