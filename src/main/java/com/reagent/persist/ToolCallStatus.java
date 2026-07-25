package com.reagent.persist;

/**
 * 工具调用账本的状态机:{@code PENDING → IN_PROGRESS → DONE | IN_DOUBT}。
 *
 * <ul>
 *   <li><b>PENDING</b>    : 模型已决定调用、但<b>还没开跑</b>(assistant 消息一落库就先记 PENDING)。
 *                          崩在这之前 = 副作用一定没发生 → 恢复时<b>安全重跑</b>。</li>
 *   <li><b>IN_PROGRESS</b>: <b>执行副作用之前</b>先 commit 的"在途"标记(带 attemptCount/startedAt)。
 *                          崩溃后若停在这态 = 副作用<b>可能做了一半、或跑完没记</b> → 这就是 exactly-once 的
 *                          "in-doubt 窗口",恢复时按工具幂等等级 + journal 对账决定怎么处置。</li>
 *   <li><b>DONE</b>       : 已执行完且结果已记录 → 恢复时直接复用结果、不再执行。</li>
 *   <li><b>IN_DOUBT</b>   : 非幂等工具崩在 in-doubt 窗口、又无 journal 完成记录 → 副作用是否发生<b>不可知</b>。
 *                          系统<b>不自动重试</b>(避免重复副作用),回模型一条"结果未知"的观察,交由其核对/重发。</li>
 * </ul>
 *
 * <p>为什么需要 IN_PROGRESS:没有它,"从没开跑(PENDING)"和"开跑没收尾"无法区分,只能一律重跑 = at-least-once。
 * 把"执行前"这个事实<b>持久化</b>,才能在恢复时把"一定没做"与"可能做了"分开——这是 exactly-once 的地基。</p>
 */
public enum ToolCallStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    IN_DOUBT,
    REJECTED
}
