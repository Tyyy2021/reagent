package com.reagent.core;

import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ★ M7 Stage2:失效扫描 —— 真·失败转移的触发器。
 *
 * <p>每个 worker 都周期性扫一遍"{@code RUNNING} 且无主或租约已过期"的任务:租约过期 = 原 owner 已停止心跳
 * 续租(崩溃 / 进程被杀 / 网络分区),无主则覆盖历史/异常创建窗口；随后尝试 claim 接管(原子,抢不到就跳过)。</p>
 *
 * <p>活着的 owner 在持续续租,其租约永不过期、不会被扫到 —— 正常运行的任务不会被误抢。
 * 新任务出生即带租约(见 {@code StateStore.createTask})，无主分支主要用于兼容历史行并保证失效恢复完备。</p>
 *
 * <p>这把过去"仅启动时扫一次全量 RUNNING"({@link CrashRecovery})升级成"任意 worker、持续扫描接管"——
 * 单点崩溃不再需要等"那台机重启",任意活着的 worker 都能在租约过期后顶上。</p>
 */
@Component
public class FailoverScanner {

    private static final Logger log = LoggerFactory.getLogger(FailoverScanner.class);

    private final StateStore stateStore;
    private final FailoverService failover;
    private final boolean enabled;
    private final int batch;

    public FailoverScanner(StateStore stateStore, FailoverService failover,
                           @Value("${reagent.recovery.enabled:true}") boolean enabled,
                           @Value("${reagent.worker.scan-batch:50}") int batch) {
        this.stateStore = stateStore;
        this.failover = failover;
        this.enabled = enabled;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${reagent.worker.scan-ms:10000}",
            initialDelayString = "${reagent.worker.scan-ms:10000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            List<TaskEntity> recoverable = stateStore.findRecoverable(batch);
            if (recoverable.isEmpty()) {
                return;
            }
            log.info("恢复扫描:发现 {} 个无主或租约过期的 RUNNING 任务,尝试接管......", recoverable.size());
            for (TaskEntity t : recoverable) {
                failover.submit(t.getId());
            }
        } catch (RuntimeException ex) {
            // 调度方法里抛异常会【中止后续所有调度】,必须吞掉,等下一轮再试
            log.error("失效扫描出错(忽略,等下一轮)", ex);
        }
    }
}
