package com.reagent.persist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tool_call 账本状态机的纯单测:{@code PENDING → IN_PROGRESS → DONE | IN_DOUBT}。
 *
 * <p>压 exactly-once 的地基:"开跑前"这个事实被 {@code markInProgress} 持久化(状态翻成 IN_PROGRESS、
 * attemptCount 累加、startedAt 落点),恢复时才能把"一定没做(PENDING)"与"可能做了(IN_PROGRESS)"分开。</p>
 */
class ToolCallEntityTest {

    private ToolCallEntity newCall() {
        return new ToolCallEntity("call-1", "task-1", "run_command", "{}");
    }

    @Test
    void 初始为PENDING且attempt为0() {
        ToolCallEntity e = newCall();
        assertEquals(ToolCallStatus.PENDING, e.getStatus());
        assertEquals(0, e.getAttemptCount());
        assertNull(e.getStartedAt(), "还没开跑,startedAt 应为 null");
    }

    @Test
    void markInProgress_置IN_PROGRESS并累加attempt和记startedAt() {
        ToolCallEntity e = newCall();
        e.markInProgress();
        assertEquals(ToolCallStatus.IN_PROGRESS, e.getStatus());
        assertEquals(1, e.getAttemptCount());
        assertNotNull(e.getStartedAt());
    }

    @Test
    void 多次markInProgress_attemptCount累加() {
        ToolCallEntity e = newCall();
        e.markInProgress();
        e.markInProgress();
        e.markInProgress();
        assertEquals(3, e.getAttemptCount(), "每次重试都该 +1,供 per-tool 上限止损");
    }

    @Test
    void inProgress到DONE_记结果() {
        ToolCallEntity e = newCall();
        e.markInProgress();
        e.markDone("命令执行完毕");
        assertEquals(ToolCallStatus.DONE, e.getStatus());
        assertEquals("命令执行完毕", e.getResult());
    }

    @Test
    void inProgress到IN_DOUBT_记存疑结果() {
        ToolCallEntity e = newCall();
        e.markInProgress();
        e.markInDoubt("⚠️ 结果未知");
        assertEquals(ToolCallStatus.IN_DOUBT, e.getStatus());
        assertEquals("⚠️ 结果未知", e.getResult());
    }
}
