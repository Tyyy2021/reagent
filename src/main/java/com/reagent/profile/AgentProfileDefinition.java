package com.reagent.profile;

import java.util.List;
import java.util.Objects;

/** Validated runtime definition from which a new task snapshot is created. */
public record AgentProfileDefinition(
        String profileId,
        String profileVersion,
        String systemPrompt,
        String knowledgeBaseId,
        String knowledgeIndexVersion,
        List<String> mcpServerIds,
        List<String> toolNames
) {
    public static final String CODING_SYSTEM_PROMPT = """
            你是一个能够调用工具来完成任务的智能体(agent)。
            根据用户给出的目标,自主判断需要哪些信息,调用合适的工具一步步推进。
            每次只需决定下一步:要么调用一个工具,要么在信息足够时直接给出最终回答。
            当你认为任务已经完成,用简洁的自然语言给出最终结论,不要再调用工具。
            """;

    public static AgentProfileDefinition coding() {
        return new AgentProfileDefinition(
                "coding",
                "v1",
                CODING_SYSTEM_PROMPT,
                null,
                null,
                List.of(),
                List.of("read_file", "list_dir", "write_file", "run_command", "sleep_ms"));
    }

    public AgentProfileDefinition {
        profileId = requireText(profileId, "profileId");
        profileVersion = requireText(profileVersion, "profileVersion");
        systemPrompt = requireText(systemPrompt, "systemPrompt");
        mcpServerIds = List.copyOf(mcpServerIds == null ? List.of() : mcpServerIds);
        toolNames = List.copyOf(Objects.requireNonNull(toolNames, "toolNames"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
