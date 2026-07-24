package com.reagent.mcp;

import java.util.List;
import java.util.Map;

public interface McpGateway extends AutoCloseable {

    List<McpRemoteTool> discover(String serverId);

    McpCallResult call(String serverId, String toolName, Map<String, Object> arguments);

    @Override
    void close();
}
