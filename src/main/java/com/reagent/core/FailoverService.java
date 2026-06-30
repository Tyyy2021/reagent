package com.reagent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ★ M7 Stage2:接管/恢复的统一入口(在虚拟线程上跑)。
 *
 * <p>启动崩溃恢复({@link CrashRecovery})与运行期失效扫描({@link FailoverScanner})都把"接管某任务"
 * 这件事委托到这里 —— DRY,且都走 {@link AgentRunner#recover}:内部先原子 claim 抢租约,
 * <b>抢到才跑、抢不到干净跳过</b>。所以多个 worker 的扫描器同时盯上同一个孤儿任务也无妨:claim 原子,
 * 只有一个能真正接管。</p>
 *
 * <p>用 JDK21 虚拟线程:接管不阻塞调度线程 / 启动线程,每任务一根虚拟线程极轻。</p>
 */
@Component
public class FailoverService {

    private static final Logger log = LoggerFactory.getLogger(FailoverService.class);

    private final AgentRunner runner;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public FailoverService(AgentRunner runner) {
        this.runner = runner;
    }

    /** 在虚拟线程上尝试接管/恢复一个任务;claim 落败者由 {@link AgentRunner#recover} 内部干净跳过。 */
    public void submit(String taskId) {
        pool.submit(() -> {
            try {
                runner.recover(taskId);
            } catch (RuntimeException ex) {
                log.error("接管/恢复任务 {} 失败", taskId, ex);
            }
        });
    }
}
