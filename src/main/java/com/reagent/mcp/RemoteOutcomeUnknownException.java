package com.reagent.mcp;

public class RemoteOutcomeUnknownException extends RuntimeException {

    public RemoteOutcomeUnknownException(String toolName, Throwable cause) {
        super("Remote MCP outcome is unknown for " + safeToolName(toolName), cause);
    }

    private static String safeToolName(String toolName) {
        if (toolName == null || !McpProperties.LEGAL_ID.matcher(toolName).matches()) {
            return "configured tool";
        }
        return toolName;
    }
}
