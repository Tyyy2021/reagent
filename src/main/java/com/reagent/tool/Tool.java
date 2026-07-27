package com.reagent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 工具统一接口 —— agent 的"手脚"。
 *
 * 实现一个新工具 = 实现这个接口 + 标 @Component,ToolRegistry 会自动注册。
 * name/description/parameterSchema 会被拼成 function-calling 规格交给模型,
 * 模型据此决定调不调、传什么参数;execute 真正干活。
 */
public interface Tool {

    /** 工具名,模型用它来指定调用,如 read_file。需全局唯一 */
    String name();

    /** 给模型看的说明,要写清楚这个工具"什么时候用、能干啥" */
    String description();

    /**
     * 参数的 JSON Schema(对象形态)。例如:
     * {
     *   "type": "object",
     *   "properties": { "path": {"type":"string","description":"文件路径"} },
     *   "required": ["path"]
     * }
     */
    Map<String, Object> parameterSchema();

    /**
     * 执行工具。
     * @param args 模型传来的参数(已解析为 JSON 节点)
     * @param ctx  执行上下文(当前 taskId、独立工作目录等)。多数工具用不到、可忽略;
     *             需要在本任务隔离工作目录里干活的工具(如 run_command)才会用到。
     * @return 给模型看的文本结果
     */
    String execute(JsonNode args, ToolContext ctx) throws Exception;

    /**
     * 工具的幂等等级,决定崩溃在 in-doubt 窗口时这次调用能否被安全重放(见 {@link IdempotencyClass})。
     *
     * <p><b>默认 fail-closed = SIDE_EFFECTFUL</b>:没显式声明的工具一律按"有副作用、重放危险"对待——
     * 宁可在崩溃恢复时把一个其实只读的工具误判成 in-doubt 上报(过度保守、不影响正确性),
     * 也绝不因为作者忘了声明,就把一个真有副作用的工具悄悄重放两次。只读 / 幂等工具<b>显式覆盖</b>声明即可。</p>
     */
    default IdempotencyClass idempotency() {
        return IdempotencyClass.SIDE_EFFECTFUL;
    }

    /** Approval is independent from replay safety; existing local tools remain immediately executable. */
    default ApprovalPolicy approvalPolicy() {
        return ApprovalPolicy.NONE;
    }
}
