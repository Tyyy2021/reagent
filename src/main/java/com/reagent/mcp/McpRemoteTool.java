package com.reagent.mcp;

import java.util.Map;

public record McpRemoteTool(
        String serverId,
        String name,
        String description,
        Map<String, Object> inputSchema
) {}
