package com.reagent.core;

import java.util.List;
import java.util.Map;

/**
 * 模型每一轮的决策结果。两种可能:
 *  1. isFinal=true  -> 任务完成,answer 是最终回答
 *  2. isFinal=false -> 要调工具,toolCalls 是这一轮要调的全部工具
 *
 * 注意:模型一轮里可能要求并行调用多个工具(parallel tool calls),
 * 所以这里用 List。OpenAI 协议要求:assistant 消息里有几个 tool_call,
 * 就必须回几条 tool 结果,否则下一轮请求会被拒(400)。
 *
 * assistantMessage 是模型返回的原始 assistant 消息(原样保留),
 * 回工具结果前必须先把它追加进历史。
 */
public class Decision {

    private final boolean isFinal;
    private final String answer;
    private final List<ToolCall> toolCalls;
    private final Map<String, Object> assistantMessage;

    private Decision(boolean isFinal, String answer, List<ToolCall> toolCalls,
                     Map<String, Object> assistantMessage) {
        this.isFinal = isFinal;
        this.answer = answer;
        this.toolCalls = toolCalls;
        this.assistantMessage = assistantMessage;
    }

    /** 任务完成 */
    public static Decision finalAnswer(String answer, Map<String, Object> assistantMessage) {
        return new Decision(true, answer, List.of(), assistantMessage);
    }

    /** 要调工具(可能不止一个) */
    public static Decision tools(
            Map<String, Object> assistantMessage,
            List<ToolCall> toolCalls) {
        if (assistantMessage == null || toolCalls == null) {
            throw mismatchedToolCalls();
        }
        List<ToolCall> executable;
        try {
            executable = List.copyOf(toolCalls);
        } catch (NullPointerException exception) {
            throw mismatchedToolCalls();
        }
        List<ToolCall> parsed = ToolCall.parseAssistantToolCalls(
                assistantMessage.get("tool_calls"));
        if (!parsed.equals(executable)) {
            throw mismatchedToolCalls();
        }
        return new Decision(false, null, executable, assistantMessage);
    }

    private static IllegalArgumentException mismatchedToolCalls() {
        return new IllegalArgumentException(
                "Assistant tool calls do not match decision");
    }

    public boolean isFinal() { return isFinal; }
    public String getAnswer() { return answer; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public Map<String, Object> getAssistantMessage() { return assistantMessage; }
}
