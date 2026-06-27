package com.reagent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具执行配置,对应 application.yml 里的 reagent.tool.*(M3 新增)。
 */
@ConfigurationProperties(prefix = "reagent.tool")
public class ToolProperties {

    /**
     * 单个工具执行的超时(毫秒)。超过则中断该工具,把"超时"当作结果回给模型,
     * 不拖垮同一轮的其它工具,也不让 agent 整体卡死。
     */
    private long timeoutMs = 30_000;

    /**
     * 同一轮的多个 tool_call 是否并发执行。
     * 关掉则退回串行(便于排查问题 / 做"串行 vs 并发"的对照演示)。
     */
    private boolean concurrent = true;

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public boolean isConcurrent() { return concurrent; }
    public void setConcurrent(boolean concurrent) { this.concurrent = concurrent; }
}
