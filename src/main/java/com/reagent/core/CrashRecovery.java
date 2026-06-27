package com.reagent.core;

import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ★ M2 杀手锏:崩溃恢复。
 *
 * 应用启动完成后扫一遍数据库:凡是还停在 RUNNING 的任务,都是"上次进程被强杀时
 * 没跑完的"——逐个用 {@link AgentRunner#resume} 接着跑。
 *
 * 用 JDK21 虚拟线程跑恢复任务:不阻塞启动、也不必担心线程数(每任务一根虚拟线程极轻)。
 *
 * 可用配置 reagent.recovery.enabled=false 关掉(做无关开发、不想自动调 LLM 花钱时)。
 */
@Component
public class CrashRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CrashRecovery.class);

    private final AgentRunner runner;
    private final StateStore stateStore;
    private final boolean enabled;
    private final int maxAttempts;

    public CrashRecovery(AgentRunner runner, StateStore stateStore,
                         @Value("${reagent.recovery.enabled:true}") boolean enabled,
                         @Value("${reagent.recovery.max-attempts:3}") int maxAttempts) {
        this.runner = runner;
        this.stateStore = stateStore;
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("崩溃恢复已关闭(reagent.recovery.enabled=false),跳过。");
            return;
        }

        List<TaskEntity> unfinished = stateStore.findRunning();
        if (unfinished.isEmpty()) {
            log.info("启动检查:没有未完成任务,无需恢复。");
            return;
        }

        log.info("启动检查:发现 {} 个未完成任务,后台恢复中......", unfinished.size());
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        for (TaskEntity task : unfinished) {
            String id = task.getId();
            // 先把恢复计数 +1 并落库(必须在 resume 之前:即便这次又崩,计数也已记下,不会无限重试)。
            // 超过上限就止损判 FAILED,避免"确定性崩溃"任务每次启动都被重跑、反复烧 LLM。
            int attempt = stateStore.incrementRecoveryCount(id);
            if (attempt > maxAttempts) {
                log.warn("任务 {} 已恢复 {} 次仍未完成,超过上限 {},止损标记 FAILED。",
                        id, attempt - 1, maxAttempts);
                stateStore.failTask(id, "超过最大自动恢复次数(" + maxAttempts + "),停止自动恢复以免反复烧钱。");
                continue;
            }
            log.info("恢复任务 {}(第 {}/{} 次尝试)", id, attempt, maxAttempts);
            pool.submit(() -> {
                try {
                    runner.resume(id);
                } catch (RuntimeException ex) {
                    log.error("恢复任务 {} 失败", id, ex);
                }
            });
        }
        pool.shutdown();   // 不再接新任务;已提交的虚拟线程继续跑完
    }
}
