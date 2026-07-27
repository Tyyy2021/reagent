package com.reagent.persist;

/**
 * 任务状态机。
 *  - RUNNING   : 进行中(进程被强杀时会停在这个状态 -> 启动时据此发现并恢复)
 *  - COMPLETED : 模型给出了最终回答,正常结束
 *  - FAILED    : 跑的过程中抛了异常(如 LLM 报错),应用层判定失败
 *  - CANCELLED : M4 用户主动取消(终态)
 *  - PAUSED    : M4 用户暂停(非终态,但不自动恢复,仅显式 resume 续跑)
 *
 * 注意:只有 RUNNING 的任务才会被崩溃恢复扫到。FAILED 不自动重试(避免死循环烧钱);
 * CANCELLED/PAUSED 同样不被自动恢复——前者是终态,后者要用户显式 resume(暂停语义=不偷偷续上)。
 */
public enum TaskStatus {
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED
}
