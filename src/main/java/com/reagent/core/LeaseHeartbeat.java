package com.reagent.core;

import com.reagent.persist.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ★ M7 Stage2:租约心跳续租。
 *
 * <p>本 worker 正在驱动的任务(={@link InFlightTasks} 里的 id),周期性把它们的租约往后续 —— 证明"我还活着、
 * 还在跑它"。只要心跳不断,这些任务的租约就永不过期,别的 worker 的失效扫描就扫不到、不会来抢。
 * 一旦本 worker 崩了 / 卡死,心跳停止 → 租约在 TTL 内过期 → 其它 worker 接管(失败转移)。</p>
 *
 * <p><b>时序不变量</b>:{@code heartbeat-ms < lease-ttl-ms}(建议 ttl/3,留 2 次容错)。续租跑在【独立调度
 * 线程】上、与任务执行解耦 —— 所以哪怕某个工具跑很久(沙箱 30s),租约也照常被续,不会假过期被误抢。</p>
 *
 * <p>续租 {@code WHERE owner = 我}:若返回"没续上",说明这任务已不归我(被接管了)。Stage2 先只告警;
 * Stage3 会据此【主动 fence】—— 旧 owner 在下个安全点自停,杜绝双驱动写花状态。</p>
 */
@Component
public class LeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final InFlightTasks inFlight;
    private final StateStore stateStore;

    public LeaseHeartbeat(InFlightTasks inFlight, StateStore stateStore) {
        this.inFlight = inFlight;
        this.stateStore = stateStore;
    }

    @Scheduled(fixedDelayString = "${reagent.worker.heartbeat-ms:10000}",
            initialDelayString = "${reagent.worker.heartbeat-ms:10000}")
    public void beat() {
        try {
            for (String taskId : inFlight.snapshot()) {
                if (!stateStore.renew(taskId)) {
                    // 续租 0 行 = 这任务已不属于本 worker(被接管 / 已释放)。Stage3 将据此主动停驱动。
                    log.warn("续租失败:任务 {} 已不属于本 worker(可能已被接管),将在 Stage3 据此自停。", taskId);
                }
            }
        } catch (RuntimeException ex) {
            // 调度方法抛异常会中止后续调度,必须吞掉
            log.error("心跳续租出错(忽略,等下一轮)", ex);
        }
    }
}
