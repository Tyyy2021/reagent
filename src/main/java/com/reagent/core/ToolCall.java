package com.reagent.core;

import java.util.regex.Pattern;

/**
 * 模型发起的一次工具调用。
 *
 * @param id        本次调用的唯一 id(OpenAI 协议要求把它带回工具结果里)
 * @param name      工具名,如 read_file
 * @param arguments 字符串形式的 JSON 参数,如 {"path":"src/Main.java"}
 */
public record ToolCall(String id, String name, String arguments) {

    private static final Pattern LEGAL_ID =
            Pattern.compile("^[A-Za-z0-9_-]{1,255}$");
    private static final Pattern LEGAL_NAME =
            Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public ToolCall {
        if (id == null || !LEGAL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid tool call id");
        }
        if (name == null || !LEGAL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid tool call name");
        }
    }
}
