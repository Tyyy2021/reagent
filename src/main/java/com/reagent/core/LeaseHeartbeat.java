package com.reagent.core;

import com.reagent.persist.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ★ M7 Stage2/3:租约心跳续租 + fence 检测。
 *
 * <p>本 worker 正在驱动的任务(={@link TaskControl} 登记的句柄,带各自的租约 epoch),周期性把租约往后续。
 * 续租 {@code WHERE owner=me AND epoch=myEpoch}:</p>
 * <ul>
 *   <li>续上(1 行)= 仍归我,租约延期 → 别的 worker 扫不到、抢不走;</li>
 *   <li>续不上(0 行)= 已被别的 worker claim 接管(epoch 被 +1)→ {@link TaskControl#markFenced} 打 FENCED,
 *       drive 在下个安全点干净停手(M7 Stage3 fencing,杜绝 GC 停顿 / 网络分区下的脑裂双驱动)。</li>
 * </ul>
 *
 * <p><b>时序不变量</b>:{@code heartbeat-ms < lease-ttl-ms}(建议 ttl/3,留 2 次容错)。续租跑在【独立调度
 * 线程】、与任务执行解耦 —— 哪怕某个工具跑很久(沙箱 30s),租约也照常被续,不会假过期被误抢。</p>
 */
@Component
public class LeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final TaskControl taskControl;
    private final StateStore stateStore;

    public LeaseHeartbeat(TaskControl taskControl, StateStore stateStore) {
        this.taskControl = taskControl;
        this.stateStore = stateStore;
    }

    @Scheduled(fixedDelayString = "${reagent.worker.heartbeat-ms:10000}",
            initialDelayString = "${reagent.worker.heartbeat-ms:10000}")
    public void beat() {
        try {
            for (TaskRunToken token : taskControl.ownedTokens()) {
                String taskId = token.taskId();
                if (!stateStore.renew(token)) {
                    log.warn("续租失败:任务 {} 的租约已被其它 worker 接管(epoch 不匹配),标记 FENCED,将在安全点自停。", taskId);
                    taskControl.markFenced(taskId);
                }
            }
        } catch (RuntimeException ex) {
            // 调度方法抛异常会中止后续调度,必须吞掉
            log.error("心跳续租出错(忽略,等下一轮)", ex);
        }
    }
}
