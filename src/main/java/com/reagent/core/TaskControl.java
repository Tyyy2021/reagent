package com.reagent.core;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务打断控制(M4 Stage3 起;M7 Stage3 加 fencing)。与 {@link InFlightTasks}(单飞护栏)分开,避免动它已有的测试。
 *
 * <p>每个正被 {@code drive} 的任务登记一个 {@link Handle}:打断信号(NONE/PAUSE/CANCEL/FENCED)+ force 标志 +
 * 驱动线程引用 + 本次驱动持有的租约 epoch。取消/暂停端点在 Tomcat 线程上设信号、心跳线程发现租约被接管时设
 * FENCED,drive 循环在 agent 虚拟线程上于<b>安全点</b>查信号——用并发容器兜住跨线程。</p>
 */
@Component
public class TaskControl {

    public enum Signal { NONE, PAUSE, CANCEL, FENCED }

    /** 一个在跑任务的控制句柄。字段 volatile:跨线程读写(端点/心跳写、drive 读)。 */
    public static final class Handle {
        private volatile Signal signal = Signal.NONE;
        private volatile boolean force = false;
        private final Thread driver;
        /** M7 Stage3:本次驱动持有的租约 epoch(fencing token);心跳据此带 epoch 续租。 */
        private final long epoch;

        Handle(Thread driver, long epoch) { this.driver = driver; this.epoch = epoch; }

        public Signal signal() { return signal; }
        public boolean force() { return force; }
        public Thread driver() { return driver; }
        public long epoch() { return epoch; }
    }

    private final ConcurrentHashMap<String, Handle> handles = new ConcurrentHashMap<>();

    /** drive 开始时登记当前(虚拟)线程为该任务的驱动线程,并记下本次驱动持有的租约 epoch。 */
    public void begin(String taskId, long epoch) {
        handles.put(taskId, new Handle(Thread.currentThread(), epoch));
    }

    /** drive 结束时注销。 */
    public void end(String taskId) {
        handles.remove(taskId);
    }

    /**
     * 请求取消。{@code force=true} 为硬杀升级(Stage3b:立即打断 in-flight 工具 -> in-doubt)。
     * @return 是否设置成功(任务不在本进程跑则 false:可能已结束 / 不在此 worker)
     */
    public boolean requestCancel(String taskId, boolean force) {
        Handle h = handles.get(taskId);
        if (h == null) return false;
        h.force = force;
        h.signal = Signal.CANCEL;
        if (force) {
            h.driver.interrupt();   // 3b 硬杀:打断驱动线程 -> 正在执行的沙箱据中断 kill -9 子进程组 / 杀容器
        }
        return true;
    }

    /** 请求暂停(优雅:当前工具批次跑完后于安全点停,留下可 resume 的干净状态)。 */
    public boolean requestPause(String taskId) {
        Handle h = handles.get(taskId);
        if (h == null) return false;
        h.signal = Signal.PAUSE;
        return true;
    }

    /**
     * ★ M7 Stage3:标记该任务已被其它 worker 接管(心跳带 epoch 续租落空时调用)。
     * drive 在下个安全点查到 FENCED 即干净停手、且<b>绝不改任务状态</b>(状态归接管者)。
     * FENCED 优先级最高(被接管必须停),直接覆盖既有信号。
     */
    public void markFenced(String taskId) {
        Handle h = handles.get(taskId);
        if (h != null) h.signal = Signal.FENCED;
    }

    /** 当前信号;任务不在跑(未登记)返回 {@link Signal#NONE}。 */
    public Signal signalOf(String taskId) {
        Handle h = handles.get(taskId);
        return h == null ? Signal.NONE : h.signal;
    }

    /** force 标志(Stage3b 硬杀用);未登记返回 false。 */
    public boolean isForced(String taskId) {
        Handle h = handles.get(taskId);
        return h != null && h.force;
    }

    /** 该任务是否正被本进程 drive(登记中)。 */
    public boolean isRunning(String taskId) {
        return handles.containsKey(taskId);
    }

    /** ★ M7 Stage3:本进程当前在驱动的任务 → 其持有 epoch 的快照(供心跳带 epoch 续租)。 */
    public Map<String, Long> ownedEpochs() {
        Map<String, Long> m = new HashMap<>();
        handles.forEach((id, h) -> m.put(id, h.epoch()));
        return m;
    }
}
