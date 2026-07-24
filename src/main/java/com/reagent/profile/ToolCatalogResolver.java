package com.reagent.profile;

import com.reagent.mcp.McpProperties;
import com.reagent.mcp.McpContractException;
import com.reagent.mcp.McpToolAdapter;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates persisted tool snapshots and later binds them to exact runtime beans. */
@Component
public class ToolCatalogResolver {

    private final ToolRegistry registry;
    private final SchemaHasher schemaHasher;
    private final ToolProperties toolProperties;
    private final McpProperties mcpProperties;

    public ToolCatalogResolver(ToolRegistry registry, SchemaHasher schemaHasher, ToolProperties toolProperties) {
        this(registry, schemaHasher, toolProperties, null);
    }

    @Autowired
    public ToolCatalogResolver(
            ToolRegistry registry,
            SchemaHasher schemaHasher,
            ToolProperties toolProperties,
            McpProperties mcpProperties) {
        this.registry = registry;
        this.schemaHasher = schemaHasher;
        this.toolProperties = toolProperties;
        this.mcpProperties = mcpProperties;
    }

    public TaskProfileSnapshot snapshot(AgentProfileDefinition definition) {
        validateProfileServers(definition.mcpServerIds());
        List<ToolSnapshot> tools = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : definition.toolNames()) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException("Duplicate tool in profile " + definition.profileId() + ": " + name);
            }
            Tool tool = registry.get(name);
            if (tool == null) {
                throw new IllegalArgumentException("Profile " + definition.profileId() + " references unknown tool: " + name);
            }
            Map<String, Object> parameterSchema = tool.parameterSchema();
            long timeoutMs = toolProperties.getTimeoutMs();
            String provider = "local";
            if (tool instanceof McpToolAdapter mcpTool) {
                if (!definition.mcpServerIds().contains(mcpTool.serverId())) {
                    throw new McpContractException(
                            "Profile does not allow MCP server: " + mcpTool.serverId());
                }
                timeoutMs = mcpTool.requestTimeoutMs();
                provider = "mcp:" + mcpTool.serverId();
            }
            tools.add(new ToolSnapshot(
                    tool.name(),
                    tool.name(),
                    tool.description(),
                    parameterSchema,
                    schemaHasher.hash(parameterSchema),
                    tool.idempotency(),
                    tool.approvalPolicy(),
                    timeoutMs,
                    provider));
        }
        return new TaskProfileSnapshot(
                definition.profileId(),
                definition.profileVersion(),
                definition.systemPrompt(),
                sha256(definition.systemPrompt()),
                definition.knowledgeBaseId(),
                definition.knowledgeIndexVersion(),
                definition.mcpServerIds(),
                tools);
    }

    public TaskToolCatalog resolve(TaskProfileSnapshot persistedSnapshot) {
        Map<String, Tool> runtimeTools = new LinkedHashMap<>();
        for (ToolSnapshot frozen : persistedSnapshot.tools()) {
            if (runtimeTools.containsKey(frozen.name())) {
                throw new ToolSchemaDriftException(
                        "Persisted profile contains duplicate tool: " + frozen.name());
            }
            Tool runtime = registry.get(frozen.name());
            if (runtime == null) {
                throw new ToolSchemaDriftException(
                        "Persisted tool is unavailable: " + frozen.name());
            }
            String frozenSchemaHash = schemaHasher.hash(frozen.parameterSchema());
            if (!frozen.schemaHash().equals(frozenSchemaHash)) {
                throw new ToolSchemaDriftException(
                        "Persisted schema hash is invalid for tool " + frozen.name());
            }
            String runtimeSchemaHash = schemaHasher.hash(runtime.parameterSchema());
            if (!frozen.schemaHash().equals(runtimeSchemaHash)) {
                throw new ToolSchemaDriftException(
                        "Tool schema drift for " + frozen.name()
                                + ": expected " + frozen.schemaHash()
                                + ", found " + runtimeSchemaHash);
            }
            runtimeTools.put(frozen.name(), runtime);
        }
        return new TaskToolCatalog(persistedSnapshot, runtimeTools);
    }

    private void validateProfileServers(List<String> serverIds) {
        Set<String> seen = new HashSet<>();
        for (String serverId : serverIds) {
            if (!seen.add(serverId)) {
                throw new McpContractException("Duplicate MCP server in profile: " + serverId);
            }
            if (mcpProperties != null) {
                mcpProperties.requireServer(serverId);
            }
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
