package com.reagent.tool;

/**
 * 模型给的文件路径越出了本任务工作区时抛出。
 *
 * <p>由 {@link ToolContext#resolveInWorkspace(String)} 抛出,被 {@link ToolExecutor} 的兜底
 * catch 捕获后,消息原样回给模型——这是一条正常的"观察结果"(路径被拒),模型据此换条工作区内的路径重试,
 * 不是 agent 崩溃。</p>
 */
public class WorkspaceEscapeException extends RuntimeException {

    public WorkspaceEscapeException(String path) {
        super("路径越出任务工作区,已拒绝(只能访问本任务工作区内的文件):" + path);
    }
}
