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

/**
 * ★ M2 杀手锏 + M7 升级:启动崩溃恢复。
 *
 * <p>应用启动完成后扫一遍 RUNNING 任务,逐个委托 {@link FailoverService} 尝试接管。M7 后接管走
 * {@link AgentRunner#recover} —— 内部先原子 claim:抢得到的(无主 / 自己崩前持有 owner=me / 别的 worker
 * 已死租约过期)才恢复,抢不到的(别的 worker 正活着跑、租约被心跳续着)干净跳过。于是【多 worker 同时启动】
 * 也安全 —— 不再像单机假设那样把别人正在跑的任务误抢过来。</p>
 *
 * <p>这里只管"启动这一下"的快速恢复(尤其本机崩前自己的任务,owner=me 可【秒认领】,不必等租约过期);
 * 运行期持续的失败转移由 {@link FailoverScanner} 周期扫描负责。恢复计数 + 止损已下沉到 {@code recover()},
 * 此处不再重复。用 reagent.recovery.enabled=false 可整体关掉自动恢复 / 扫描。</p>
 */
@Component
public class CrashRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CrashRecovery.class);

    private final StateStore stateStore;
    private final FailoverService failover;
    private final boolean enabled;

    public CrashRecovery(StateStore stateStore, FailoverService failover,
                         @Value("${reagent.recovery.enabled:true}") boolean enabled) {
        this.stateStore = stateStore;
        this.failover = failover;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("崩溃恢复已关闭(reagent.recovery.enabled=false),跳过。");
            return;
        }
        List<TaskEntity> unfinished = stateStore.findRunning();
        if (unfinished.isEmpty()) {
            log.info("启动检查:没有 RUNNING 任务,无需恢复。");
            return;
        }
        log.info("启动检查:发现 {} 个 RUNNING 任务,逐个尝试认领恢复(只领得到无主 / 自己 / 租约过期的)......",
                unfinished.size());
        for (TaskEntity task : unfinished) {
            failover.submit(task.getId());
        }
    }
}
