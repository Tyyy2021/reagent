package com.reagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reagent.core.Decision;
import com.reagent.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式拼装器单测(M4 Stage2)。用程序化构造 JsonNode 喂"录制好的" delta 序列,
 * 不写多层转义的 JSON 字符串(免得测试自身出 bug),专攻最易错的分片 tool_call 拼装。
 */
class StreamingDecisionAssemblerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode contentDelta(String text) {
        ObjectNode d = mapper.createObjectNode();
        d.put("content", text);
        return d;
    }

    /** 构造一个 tool_calls delta 帧;id/name/argsFragment 传 null 表示该帧不带这部分(模拟分片到达)。 */
    private JsonNode toolCallDelta(int index, String id, String name, String argsFragment) {
        ObjectNode d = mapper.createObjectNode();
        ArrayNode tcs = d.putArray("tool_calls");
        ObjectNode tc = tcs.addObject();
        tc.put("index", index);
        if (id != null) {
            tc.put("id", id);
        }
        ObjectNode fn = tc.putObject("function");
        if (name != null) {
            fn.put("name", name);
        }
        if (argsFragment != null) {
            fn.put("arguments", argsFragment);
        }
        return d;
    }

    @Test
    void assemblesPlainContentAndEmitsTokensInOrder() {
        List<String> tokens = new ArrayList<>();
        StreamingDecisionAssembler asm = new StreamingDecisionAssembler(tokens::add);
        asm.acceptDelta(contentDelta(""));        // 首帧常是 role + 空 content,应被忽略
        asm.acceptDelta(contentDelta("He"));
        asm.acceptDelta(contentDelta("llo"));
        asm.acceptDelta(mapper.createObjectNode()); // 末帧空 delta(只带 finish_reason 的情形)

        Decision d = asm.build();
        assertTrue(d.isFinal());
        assertEquals("Hello", d.getAnswer());
        assertEquals(List.of("He", "llo"), tokens);   // 逐字、按序吐出,空片不吐
    }

    @Test
    void assemblesFragmentedToolCallArguments() {
        StreamingDecisionAssembler asm = new StreamingDecisionAssembler(null);
        // name + id 只在首帧;arguments 跨三帧分片续拼成 {"command": "ls"}
        asm.acceptDelta(toolCallDelta(0, "call_1", "run_command", "{\"comm"));
        asm.acceptDelta(toolCallDelta(0, null, null, "and\": \"ls"));
        asm.acceptDelta(toolCallDelta(0, null, null, "\"}"));

        Decision d = asm.build();
        assertFalse(d.isFinal());
        assertEquals(1, d.getToolCalls().size());
        ToolCall c = d.getToolCalls().get(0);
        assertEquals("call_1", c.id());
        assertEquals("run_command", c.name());
        assertEquals("{\"command\": \"ls\"}", c.arguments());
    }

    @Test
    void assemblesMultipleParallelToolCallsByIndex() {
        StreamingDecisionAssembler asm = new StreamingDecisionAssembler(null);
        // 两个并行工具,各自分片、交错到达,靠 index 区分
        asm.acceptDelta(toolCallDelta(0, "a", "sleep_ms", "{\"ms\""));
        asm.acceptDelta(toolCallDelta(1, "b", "sleep_ms", "{\"ms\""));
        asm.acceptDelta(toolCallDelta(0, null, null, ":1}"));
        asm.acceptDelta(toolCallDelta(1, null, null, ":2}"));

        Decision d = asm.build();
        assertFalse(d.isFinal());
        assertEquals(2, d.getToolCalls().size());
        assertEquals("a", d.getToolCalls().get(0).id());
        assertEquals("{\"ms\":1}", d.getToolCalls().get(0).arguments());
        assertEquals("b", d.getToolCalls().get(1).id());
        assertEquals("{\"ms\":2}", d.getToolCalls().get(1).arguments());
    }

    @Test
    void contentBeforeToolCallsStillBecomesToolsDecision() {
        List<String> tokens = new ArrayList<>();
        StreamingDecisionAssembler asm = new StreamingDecisionAssembler(tokens::add);
        asm.acceptDelta(contentDelta("let me check"));
        asm.acceptDelta(toolCallDelta(0, "x", "list_dir", "{}"));

        Decision d = asm.build();
        assertFalse(d.isFinal());                       // 只要有 tool_calls 就是 tools 决策
        assertEquals(1, d.getToolCalls().size());
        assertEquals("list_dir", d.getToolCalls().get(0).name());
        assertEquals(List.of("let me check"), tokens);  // 前导文本仍逐片吐出
    }

    @Test
    void emptyArgumentsDefaultsToEmptyJsonObject() {
        StreamingDecisionAssembler asm = new StreamingDecisionAssembler(null);
        asm.acceptDelta(toolCallDelta(0, "z", "list_dir", null));  // 全程没 arguments 片
        Decision d = asm.build();
        assertEquals(1, d.getToolCalls().size());
        assertEquals("{}", d.getToolCalls().get(0).arguments());   // 兜底成 {} 而非空串
        assertNull(d.getAnswer());                                  // tools 决策无 answer
    }

    // ====== M6:token usage(stream_options.include_usage=true 时,流末尾会多一个 choices 为空、带 usage 的 chunk)======

    private JsonNode usageNode(int prompt, int completion) {
        ObjectNode u = mapper.createObjectNode();
        u.put("prompt_tokens", prompt);
        u.put("completion_tokens", completion);
        u.put("total_tokens", prompt + completion);
        return u;
    }

    @Test
    void acceptUsage_解析token数_供span上报() {
        StreamingDecisionAssembler asm = new StreamingDecisionAssembler(null);
        asm.acceptDelta(contentDelta("hi"));
        asm.acceptUsage(usageNode(120, 8));   // 模拟流末尾的 usage chunk

        Decision d = asm.build();
        assertTrue(d.isFinal());
        assertEquals(120L, asm.getPromptTokens());
        assertEquals(8L, asm.getCompletionTokens());
    }

    @Test
    void 没有usage时token返回负一_优雅降级不报错() {
        StreamingDecisionAssembler asm = new StreamingDecisionAssembler(null);
        asm.acceptDelta(contentDelta("hi"));
        asm.acceptUsage(mapper.createObjectNode());   // 空 usage(对端没开 include_usage)
        asm.acceptUsage(null);                          // 防御:null 也不炸

        asm.build();
        assertEquals(-1L, asm.getPromptTokens());
        assertEquals(-1L, asm.getCompletionTokens());
    }
}
