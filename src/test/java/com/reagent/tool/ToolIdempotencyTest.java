package com.reagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.tool.impl.ListDirTool;
import com.reagent.tool.impl.ReadFileTool;
import com.reagent.tool.impl.RunCommandTool;
import com.reagent.tool.impl.SleepTool;
import com.reagent.tool.impl.WriteFileTool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工具幂等分级单测 —— 决策表"in-doubt 能否安全重放"的依据(gate 反证):
 * 只读 / 幂等工具可重放、有副作用工具不可。同时锁死 <b>fail-closed 默认</b>:
 * 没显式声明的工具一律按"有副作用、不重放"对待。
 */
class ToolIdempotencyTest {

    @Test
    void 只读工具_READ_ONLY() {
        assertEquals(IdempotencyClass.READ_ONLY, new ReadFileTool().idempotency());
        assertEquals(IdempotencyClass.READ_ONLY, new ListDirTool().idempotency());
        assertEquals(IdempotencyClass.READ_ONLY, new SleepTool().idempotency());
    }

    @Test
    void 覆盖写_IDEMPOTENT() {
        assertEquals(IdempotencyClass.IDEMPOTENT, new WriteFileTool().idempotency());
    }

    @Test
    void 任意shell_SIDE_EFFECTFUL() {
        // 只调 idempotency(),不碰 sandbox,构造参数传 null 即可
        assertEquals(IdempotencyClass.SIDE_EFFECTFUL, new RunCommandTool(null, null).idempotency());
    }

    @Test
    void 未显式声明的工具_默认fail_closed为SIDE_EFFECTFUL() {
        Tool unclassified = new Tool() {
            @Override public String name() { return "mystery"; }
            @Override public String description() { return "没声明幂等等级的工具"; }
            @Override public Map<String, Object> parameterSchema() { return Map.of(); }
            @Override public String execute(JsonNode args, ToolContext ctx) { return "x"; }
        };
        assertEquals(IdempotencyClass.SIDE_EFFECTFUL, unclassified.idempotency(),
                "fail-closed:忘了声明就当有副作用,绝不悄悄重放");
    }
}
