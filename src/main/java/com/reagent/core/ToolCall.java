package com.reagent.core;

/**
 * 模型发起的一次工具调用。
 *
 * @param id        本次调用的唯一 id(OpenAI 协议要求把它带回工具结果里)
 * @param name      工具名,如 read_file
 * @param arguments 字符串形式的 JSON 参数,如 {"path":"src/Main.java"}
 */
public record ToolCall(String id, String name, String arguments) {
}
