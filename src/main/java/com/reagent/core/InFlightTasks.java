package com.reagent.core;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * “同一任务单飞”护栏(单机 / JVM 内)。
 *
 * <p>用途:挡住同一个任务被两个线程并发驱动——典型是“启动自动恢复 + 用户手动 resume 撞车”,
 * 或用户连点两次 resume。若不挡,两个 {@code drive} 会撞 {@code message.seq}、还会把同一工具
 * 跑两遍(账本的 DONE 检查与工具执行之间不是原子的)。</p>
 *
 * <p><b>边界</b>:这是 <b>JVM 内</b>护栏(一个 {@code ConcurrentHashMap} 的 key 集合)。
 * 跨进程(多 worker 各自 resume 同一任务)的同源竞态它管不了,那要 DB 层租约/乐观锁——是 M7 的活;
 * 在那之前,{@code message} 表 {@code (task_id, seq)} 唯一约束会让跨进程撞车 fail-fast。</p>
 */
@Component
public class InFlightTasks {

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** 尝试占用某任务的执行权;返回 true=占到(可驱动),false=已被占(应跳过本次驱动)。 */
    public boolean tryBegin(String taskId) {
        return running.add(taskId);
    }

    /** 释放执行权(必须在 finally 里调,否则该任务会被永久挡住)。 */
    public void end(String taskId) {
        running.remove(taskId);
    }

    /** 当前是否正在执行(调试 / 观测用)。 */
    public boolean isRunning(String taskId) {
        return running.contains(taskId);
    }
}
