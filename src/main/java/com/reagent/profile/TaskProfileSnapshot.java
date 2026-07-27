package com.reagent.profile;

import java.util.List;
import java.util.Objects;

/** Frozen profile and allowlisted tool catalog persisted on the task row. */
public record TaskProfileSnapshot(
        String profileId,
        String profileVersion,
        String systemPrompt,
        String systemPromptHash,
        String knowledgeBaseId,
        String knowledgeIndexVersion,
        List<String> mcpServerIds,
        List<ToolSnapshot> tools
) {
    public TaskProfileSnapshot {
        profileId = Objects.requireNonNull(profileId, "profileId");
        profileVersion = Objects.requireNonNull(profileVersion, "profileVersion");
        systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        systemPromptHash = Objects.requireNonNull(systemPromptHash, "systemPromptHash");
        mcpServerIds = List.copyOf(mcpServerIds == null ? List.of() : mcpServerIds);
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    }
}
