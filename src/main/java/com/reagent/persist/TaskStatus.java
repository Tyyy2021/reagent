package com.reagent.persist;

/**
 * 任务状态机。M2 只用到三态,够演示断点续跑:
 *  - RUNNING   : 进行中(进程被强杀时会停在这个状态 -> 启动时据此发现并恢复)
 *  - COMPLETED : 模型给出了最终回答,正常结束
 *  - FAILED    : 跑的过程中抛了异常(如 LLM 报错),应用层判定失败
 *
 * 注意:只有 RUNNING 的任务才会被崩溃恢复扫到。FAILED 不会自动重试(避免死循环烧钱)。
 */
public enum TaskStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
