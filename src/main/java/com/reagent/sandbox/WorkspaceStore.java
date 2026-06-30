package com.reagent.sandbox;

import java.nio.file.Path;

/**
 * 工作区存储抽象(M7 C)—— 把"per-task 工作区在哪、怎么让接管 worker 拿到"做成可插拔。
 *
 * <p>背景:coding agent 的每任务工作区({@code sandbox.workspace-root/<taskId>})原是 worker 本地盘。
 * 多 worker 下,失败转移后<b>接管 worker 看不到原 worker 写的文件</b>,代码任务接不下去。本接口把工作区
 * 的获取 / 同步抽象出来,按 {@code reagent.workspace.store} 选后端:</p>
 * <ul>
 *   <li>{@link SharedFsWorkspaceStore}(shared,默认):所有 worker 挂同一共享盘(NFS/EFS/PVC)到
 *       workspace-root,天然互见,{@link #commit} 为 no-op。本身即云上生产形态。</li>
 *   <li>git / 对象存储(预留 drop-in):本地副本 + {@link #commit} 推回远端,接管 worker {@link #checkout} 拉最新。</li>
 * </ul>
 */
public interface WorkspaceStore {

    /**
     * 准备并返回本任务的<b>本地</b>工作目录(绝对路径,工具直接读写)。
     * 首次 = 创建;失败转移接管时再次调用 = 取到最新内容(shared 自动可见;git / 对象存储则 pull / download)。
     */
    Path checkout(String taskId);

    /**
     * 把本任务工作区到此刻的改动同步到持久后端,使其它 worker 接管时 {@link #checkout} 得到。
     * 在每步工具执行后调用(工作区可能被改的时刻)。shared-fs = no-op(共享盘实时互见)。
     */
    void commit(String taskId);
}
