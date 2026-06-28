package com.reagent.llm;

import com.reagent.core.Context;
import com.reagent.core.Decision;

import java.util.List;
import java.util.Map;

/**
 * 模型客户端接口。屏蔽不同厂商(OpenAI / Ollama / 国内中转)的差异。
 * 内核只依赖这个接口,不关心底层是谁。
 */
public interface LlmClient {

    /**
     * 把当前上下文和可用工具交给模型,返回模型的一次决策。
     *
     * @param context   到目前为止的全部对话历史(含工具调用与结果)
     * @param toolSpecs OpenAI function-calling 格式的工具规格列表
     * @return 模型的决策:要么调某个工具,要么给出最终答案
     */
    Decision chat(Context context, List<Map<String, Object>> toolSpecs);

    /**
     * 流式版(M4 Stage2):请求 {@code stream=true},把 content 增量经 {@code onToken} 逐片吐出
     * (给 SSE 逐字流),内部把<b>分片到达的 tool_calls</b> 拼回完整决策。
     * 返回与 {@link #chat} 同形的 {@link Decision},故上层循环 / 落库逻辑无需区分。
     */
    Decision chatStream(Context context, List<Map<String, Object>> toolSpecs,
                        java.util.function.Consumer<String> onToken);
}
