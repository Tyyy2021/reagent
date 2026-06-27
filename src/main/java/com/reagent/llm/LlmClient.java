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
}
