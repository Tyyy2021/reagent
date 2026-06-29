package com.reagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.core.Decision;
import com.reagent.core.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 流式 Chat 增量拼装器(M4 Stage2)。
 *
 * <p>把 OpenAI/DeepSeek 流式响应的一串 delta chunk 拼回一个 {@link Decision}:
 * <ul>
 *   <li><b>content</b> 增量边拼边经 {@code onToken} 吐出去(给 SSE 逐字流);</li>
 *   <li><b>tool_calls</b> 增量按 {@code index} 累积——<b>这是最容易出 bug 的地方</b>:
 *       name 通常只在某个 chunk 出现一次,而 arguments 是<b>跨多个 chunk 分片</b>到达的字符串,
 *       必须按 index 把碎片续拼成完整 JSON。</li>
 * </ul>
 *
 * <p>故意做成纯内存、可单测:喂录制好的 chunk 序列即可断言拼装结果,不依赖 HTTP/SSE。
 * 产出的 assistantMessage 形状与非流式 {@code chat()} 完全一致,故上层落库/重建逻辑不用改。</p>
 */
public class StreamingDecisionAssembler {

    private final Consumer<String> onToken;
    private final StringBuilder content = new StringBuilder();
    private final TreeMap<Integer, ToolCallAcc> toolCalls = new TreeMap<>();  // index -> 累积中(TreeMap 保序)
    private long promptTokens = -1;       // M6:usage(stream_options.include_usage=true 时末尾 chunk 带);-1=未拿到
    private long completionTokens = -1;

    public StreamingDecisionAssembler(Consumer<String> onToken) {
        this.onToken = onToken == null ? t -> { } : onToken;
    }

    /** 吃一个 chunk 的 {@code choices[0].delta} 节点,累积 content / tool_calls。 */
    public void acceptDelta(JsonNode delta) {
        if (delta == null || delta.isMissingNode() || delta.isNull()) {
            return;
        }

        JsonNode c = delta.get("content");
        if (c != null && c.isTextual()) {
            String frag = c.asText();
            if (!frag.isEmpty()) {
                content.append(frag);
                onToken.accept(frag);
            }
        }

        JsonNode tcs = delta.get("tool_calls");
        if (tcs != null && tcs.isArray()) {
            for (JsonNode tc : tcs) {
                int index = tc.path("index").asInt(0);
                ToolCallAcc acc = toolCalls.computeIfAbsent(index, k -> new ToolCallAcc());
                JsonNode id = tc.get("id");
                if (id != null && id.isTextual()) {
                    acc.id = id.asText();
                }
                JsonNode fn = tc.get("function");
                if (fn != null) {
                    JsonNode name = fn.get("name");
                    if (name != null && name.isTextual()) {
                        acc.name = name.asText();
                    }
                    JsonNode args = fn.get("arguments");
                    if (args != null && args.isTextual()) {
                        acc.args.append(args.asText());   // 关键:分片续拼
                    }
                }
            }
        }
    }

    /**
     * 吃 chunk 顶层的 {@code usage}(M6):开启 {@code stream_options.include_usage} 后,流末尾会多一个
     * {@code choices:[]} 但带 usage 的 chunk。对端不支持时本方法收不到、保持 -1,只是缺这条属性、不报错。
     */
    public void acceptUsage(JsonNode usage) {
        if (usage == null || !usage.isObject() || usage.isEmpty()) {
            return;
        }
        JsonNode in = usage.get("prompt_tokens");
        JsonNode out = usage.get("completion_tokens");
        if (in != null && in.isNumber()) {
            promptTokens = in.asLong();
        }
        if (out != null && out.isNumber()) {
            completionTokens = out.asLong();
        }
    }

    /** 输入(prompt)token 数;-1 = 本次未取到。 */
    public long getPromptTokens() {
        return promptTokens;
    }

    /** 输出(completion)token 数;-1 = 本次未取到。 */
    public long getCompletionTokens() {
        return completionTokens;
    }

    /** 收尾:有 tool_calls -> Decision.tools;否则 -> Decision.finalAnswer。 */
    public Decision build() {
        String text = content.toString();

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");

        if (!toolCalls.isEmpty()) {
            List<Map<String, Object>> tcList = new ArrayList<>();
            List<ToolCall> calls = new ArrayList<>();
            for (ToolCallAcc acc : toolCalls.values()) {
                String args = acc.args.length() == 0 ? "{}" : acc.args.toString();
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", acc.name);
                fn.put("arguments", args);
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("id", acc.id);
                tc.put("type", "function");
                tc.put("function", fn);
                tcList.add(tc);
                calls.add(new ToolCall(acc.id, acc.name, args));
            }
            assistant.put("content", text.isEmpty() ? null : text);   // 只调工具时 content 为 null,符合协议
            assistant.put("tool_calls", tcList);
            return Decision.tools(assistant, calls);
        }

        assistant.put("content", text);
        return Decision.finalAnswer(text, assistant);
    }

    private static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }
}
