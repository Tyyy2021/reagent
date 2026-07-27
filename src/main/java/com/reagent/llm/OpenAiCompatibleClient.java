package com.reagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.Context;
import com.reagent.core.Decision;
import com.reagent.core.ToolCall;
import com.reagent.obs.Trace;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 基于 OpenAI Chat Completions 协议的 LLM 客户端。
 * 因为 Ollama / 国内中转大多兼容这套协议,所以一份实现通吃。
 *
 * 核心就是一次 HTTP POST /chat/completions,然后解析返回:
 *  - 如果模型返回了 tool_calls   -> 它要调工具      -> Decision.tools(...)
 *  - 否则返回普通 content        -> 它认为任务完成   -> Decision.finalAnswer(...)
 */
@Component
@ConditionalOnProperty(
        prefix = "reagent.llm",
        name = "mode",
        havingValue = "openai",
        matchIfMissing = true)
public class OpenAiCompatibleClient implements LlmClient {

    private final RestClient http;
    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final Tracer tracer;

    public OpenAiCompatibleClient(LlmProperties props, ObjectMapper mapper, Tracer tracer) {
        this.props = props;
        this.mapper = mapper;
        this.tracer = tracer;
        this.http = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .build();
    }

    /** 把 token usage 记到 LLM span(对齐 gen_ai.usage.*);-1 表示未取到,跳过不写。 */
    private static void recordUsage(Span span, long promptTokens, long completionTokens) {
        if (promptTokens >= 0) {
            span.setAttribute(Trace.USAGE_IN, promptTokens);
        }
        if (completionTokens >= 0) {
            span.setAttribute(Trace.USAGE_OUT, completionTokens);
        }
    }

    @Override
    public Decision chat(Context context, List<Map<String, Object>> toolSpecs) {
        // M6:LLM 调用 span(parent = 当前 agent.step);非流式路径(?sync=true / 单测)。token 留 C 步。
        Span span = tracer.spanBuilder("chat " + props.getModel())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(Trace.GENAI_SYSTEM, "deepseek")
                .setAttribute(Trace.GENAI_MODEL, props.getModel())
                .setAttribute(Trace.GENAI_OP, "chat")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // 1. 拼请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.getModel());
            body.put("messages", context.messages());
            if (toolSpecs != null && !toolSpecs.isEmpty()) {
                body.put("tools", toolSpecs);
                body.put("tool_choice", "auto");   // 让模型自己决定要不要调工具
            }

            // 2. 发请求
            JsonNode resp = http.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            // 3. 解析返回的 message
            JsonNode message = resp.path("choices").path(0).path("message");
            @SuppressWarnings("unchecked")
            Map<String, Object> assistantMessage = mapper.convertValue(message, Map.class);

            JsonNode usage = resp.path("usage");   // M6:非流式 resp 直接带 usage
            recordUsage(span, usage.path("prompt_tokens").asLong(-1), usage.path("completion_tokens").asLong(-1));

            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                // 模型要调工具:把这一轮所有 tool_calls 都解析出来,逐一回结果
                List<ToolCall> calls = new ArrayList<>();
                for (JsonNode tc : toolCalls) {
                    // 参数兼容两种返回:OpenAI 给字符串 "{...}",Ollama 可能直接给 JSON 对象
                    JsonNode argsNode = tc.path("function").path("arguments");
                    String arguments = argsNode.isTextual() ? argsNode.asText() : argsNode.toString();
                    calls.add(new ToolCall(
                            tc.path("id").asText(),
                            tc.path("function").path("name").asText(),
                            arguments
                    ));
                }
                span.setAttribute(Trace.DECISION, "tools");
                span.setAttribute(Trace.TOOL_CALLS, (long) calls.size());
                return Decision.tools(assistantMessage, calls);
            }

            // 模型给了普通回答 -> 视为任务完成
            String content = message.path("content").asText("");
            span.setAttribute(Trace.DECISION, "final");
            return Decision.finalAnswer(content, assistantMessage);
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
        }
    }

    @Override
    public Decision chatStream(Context context, List<Map<String, Object>> toolSpecs, Consumer<String> onToken) {
        // M6:LLM 调用 span(parent = 当前 agent.step);主循环走这条流式路径。token 留 C 步。
        Span span = tracer.spanBuilder("chat " + props.getModel())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(Trace.GENAI_SYSTEM, "deepseek")
                .setAttribute(Trace.GENAI_MODEL, props.getModel())
                .setAttribute(Trace.GENAI_OP, "chat")
                .setAttribute("reagent.stream", true)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // 1. 拼请求体(同 chat,多 stream=true)
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.getModel());
            body.put("messages", context.messages());
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));   // M6:让流式末尾回 usage(token 数)
            if (toolSpecs != null && !toolSpecs.isEmpty()) {
                body.put("tools", toolSpecs);
                body.put("tool_choice", "auto");
            }

            StreamingDecisionAssembler asm = new StreamingDecisionAssembler(onToken);

            // 2. 用 exchange 拿原始响应流,逐行读 SSE:每行 "data: {chunk}" 喂拼装器,[DONE] 收尾。
            //    (exchange 不对 body 做对象转换,可边收边读;content 增量经 onToken 实时流出。)
            http.post()
                    .uri("/chat/completions")
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            String err = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new IllegalStateException("LLM 流式请求失败 " + response.getStatusCode() + ": " + err);
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) {
                                    continue;   // 空行 / event: 行 / 注释,跳过
                                }
                                String data = line.substring("data:".length()).trim();
                                if (data.isEmpty()) {
                                    continue;
                                }
                                if ("[DONE]".equals(data)) {
                                    break;
                                }
                                JsonNode chunk = mapper.readTree(data);
                                asm.acceptUsage(chunk.path("usage"));   // M6:usage chunk 的 choices 为空,在这层单独捞
                                asm.acceptDelta(chunk.path("choices").path(0).path("delta"));
                            }
                        }
                        return null;
                    });

            // 3. 把增量拼成完整决策(形状与 chat() 一致,上层无需区分)
            Decision d = asm.build();
            span.setAttribute(Trace.DECISION, d.isFinal() ? "final" : "tools");
            if (!d.isFinal()) {
                span.setAttribute(Trace.TOOL_CALLS, (long) d.getToolCalls().size());
            }
            recordUsage(span, asm.getPromptTokens(), asm.getCompletionTokens());
            return d;
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
        }
    }
}
