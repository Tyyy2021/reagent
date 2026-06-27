package com.reagent.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InFlightTasks} 护栏验证 —— 不依赖 Spring / DB / LLM,纯本地、免费、可重复。
 * 重点:同一任务并发抢占,只能有一个成功(这正是挡住"自动恢复 + 手动 resume 撞车"的依据)。
 */
class InFlightTasksTest {

    private final InFlightTasks guard = new InFlightTasks();

    @Test
    void 首次占用成功_重复占用失败() {
        assertTrue(guard.tryBegin("t1"));
        assertFalse(guard.tryBegin("t1"), "同一任务第二次应占用失败");
        assertTrue(guard.isRunning("t1"));
    }

    @Test
    void 释放后可再次占用() {
        guard.tryBegin("t1");
        guard.end("t1");
        assertFalse(guard.isRunning("t1"));
        assertTrue(guard.tryBegin("t1"), "释放后应能再次占用");
    }

    @Test
    void 不同任务互不影响() {
        assertTrue(guard.tryBegin("a"));
        assertTrue(guard.tryBegin("b"), "不同任务应各自占用成功");
    }

    @Test
    void 高并发抢占_只有一个成功() throws Exception {
        int n = 64;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();                       // 所有线程卡在同一起跑线
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (guard.tryBegin("same")) {
                        winners.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, winners.get(), "并发下同一任务只能有一个占用成功");
    }
}
