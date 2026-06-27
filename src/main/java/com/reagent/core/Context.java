package com.reagent.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话上下文 —— agent 的"记忆"。内核里用 OpenAI 的 messages 数组形态。
 *
 * M1:只在内存里维护。
 * M2:每条消息会由 StateStore 同步落库;崩溃后用 {@link #fromMessages} 从库里 1:1 重建,
 *     再用 {@link #pendingToolCalls} 算出"还差哪些工具结果没补",接着把循环跑完。
 *
 * 消息有四种 role:
 *  - system    : 系统提示词
 *  - user      : 用户目标
 *  - assistant : 模型的回复(可能带 tool_calls)
 *  - tool      : 工具执行结果(需带上对应的 tool_call_id)
 */
public class Context {

    private final List<Map<String, Object>> messages = new ArrayList<>();

    public Context(String systemPrompt) {
        messages.add(message("system", systemPrompt));
    }

    /** 给重建用的私有构造:不预置 system,消息整体灌入 */
    private Context() {
    }

    /** 崩溃恢复:用已落库的历史消息重建上下文 */
    public static Context fromMessages(List<Map<String, Object>> persisted) {
        Context c = new Context();
        c.messages.addAll(persisted);
        return c;
    }

    public void addUser(String content) {
        messages.add(message("user", content));
    }

    /** 把模型返回的原始 assistant 消息原样追加(里面可能含 tool_calls) */
    public void addAssistant(Map<String, Object> assistantMessage) {
        messages.add(assistantMessage);
    }

    /** 追加一条工具执行结果 */
    public void addToolResult(String toolCallId, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId);
        m.put("content", content);
        messages.add(m);
    }

    public List<Map<String, Object>> messages() {
        return messages;
    }

    /** 当前消息条数,粗略用于停止条件 / 调试 */
    public int size() {
        return messages.size();
    }

    /**
     * 算出"还没拿到结果的工具调用"。这是断点续跑的关键:
     * 进程可能在"assistant 已要求调 N 个工具、但只补了前几个 tool 结果"时崩溃,
     * 而 OpenAI 协议要求每个 tool_call 都必须有对应 tool 结果,否则下一轮请求 400。
     *
     * 做法:找到最后一条带 tool_calls 的 assistant 消息,
     *      减去它后面已出现的 tool 结果(按 tool_call_id),剩下的就是要补跑的。
     * 正常跑完一轮后这里会返回空,循环便去问模型下一步。
     */
    public List<ToolCall> pendingToolCalls() {
        int idx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if ("assistant".equals(m.get("role")) && m.get("tool_calls") != null) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return List.of();
        }

        // 这条 assistant 之后已经回过的 tool_call_id
        Set<String> answered = new HashSet<>();
        for (int i = idx + 1; i < messages.size(); i++) {
            Map<String, Object> m = messages.get(i);
            if ("tool".equals(m.get("role"))) {
                answered.add(String.valueOf(m.get("tool_call_id")));
            }
        }

        List<ToolCall> pending = new ArrayList<>();
        Object toolCalls = messages.get(idx).get("tool_calls");
        if (toolCalls instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> tc)) continue;
                String id = String.valueOf(tc.get("id"));
                if (answered.contains(id)) continue;
                Map<?, ?> fn = (Map<?, ?>) tc.get("function");
                String name = String.valueOf(fn.get("name"));
                Object argsObj = fn.get("arguments");
                String args = argsObj instanceof String s ? s
                        : (argsObj == null ? "{}" : argsObj.toString());
                pending.add(new ToolCall(id, name, args));
            }
        }
        return pending;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
