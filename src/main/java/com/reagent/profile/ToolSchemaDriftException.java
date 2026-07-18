package com.reagent.profile;

/** Raised before any LLM/tool call when a persisted task catalog cannot bind exactly. */
public class ToolSchemaDriftException extends IllegalStateException {

    public ToolSchemaDriftException(String message) {
        super(message);
    }
}
