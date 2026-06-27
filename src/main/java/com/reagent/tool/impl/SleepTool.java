package com.reagent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 故意"慢"的工具:睡指定毫秒再返回。本身没业务意义,
 * 专门用来【演示/验证 M3 的并发执行】:
 *   让模型同一轮发起两个 sleep(各 2000ms)——
 *   串行执行总耗时 ≈ 4000ms,并发执行 ≈ 2000ms,差值即并发收益。
 * 也可用来触发"超时被中断"的路径(睡得比 reagent.tool.timeout-ms 还久)。
 */
@Component
public class SleepTool implements Tool {

    /** 上限,别让模型一不小心睡到天荒地老 */
    private static final long MAX_MS = 60_000;

    @Override
    public String name() {
        return "sleep_ms";
    }

    @Override
    public IdempotencyClass idempotency() {
        return IdempotencyClass.READ_ONLY;  // 只睡觉,无副作用:重放无害
    }

    @Override
    public String description() {
        return "等待指定的毫秒数后返回。用于演示并发或制造耗时操作,无其它副作用。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "ms", Map.of(
                                "type", "integer",
                                "description", "要等待的毫秒数,如 2000 表示等 2 秒"
                        )
                ),
                "required", List.of("ms")
        );
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws InterruptedException {
        long ms = args.path("ms").asLong(0);
        if (ms <= 0) {
            return "错误:ms 必须是正整数。";
        }
        ms = Math.min(ms, MAX_MS);
        long start = System.currentTimeMillis();
        // 可被中断:超时取消时 future.cancel(true) 会 interrupt 这根虚拟线程
        Thread.sleep(ms);
        return "已等待 " + (System.currentTimeMillis() - start) + "ms。";
    }
}
