package com.reagent.mcp;

public class McpContractException extends RuntimeException {

    public McpContractException(String message) {
        super(message);
    }

    public McpContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
