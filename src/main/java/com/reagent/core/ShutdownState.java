package com.reagent.core;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 标记"应用是否正在关闭"。
 *
 * 为什么需要它:进程关闭(IDE 点停止 / kill / 部署滚动更新)会打断正在跑的任务线程,
 * 抛出异常。这种异常【不是任务本身失败】,而是"进程没了",任务应当保持 RUNNING、
 * 等重启后由崩溃恢复接着跑。只有应用层的真错误(如 LLM 报 400)才该判 FAILED。
 *
 * 实现:Spring 关闭时会先发 ContextClosedEvent(早于 Tomcat 停机、早于请求线程被打断),
 * 我们据此把标记置位。AgentRunner 的 catch 里据此区分"关闭" vs "真失败"。
 */
@Component
public class ShutdownState {

    private volatile boolean shuttingDown = false;

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        this.shuttingDown = true;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
