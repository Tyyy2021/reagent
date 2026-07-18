package com.reagent.tool;

import com.reagent.core.TaskRunToken;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具执行上下文 —— 把“当前是哪个任务、在哪个工作目录干活”这类运行时身份,<b>显式</b>传进工具。
 *
 * <p>为什么用显式传参,而不是 ThreadLocal / ScopedValue:工具是在 {@link ToolExecutor}
 * 新起的<b>虚拟线程</b>上并发执行的——普通 ThreadLocal 跨不过这道线程边界,
 * ScopedValue 在 JDK21 还是 preview;而显式参数会被提交时的 lambda 捕获,
 * 跨线程<b>天然正确、可证明、好测</b>。</p>
 *
 * <p>这个对象也是后续把<b>取消信号(M4 中途打断)、trace span(M6 可观测)</b>
 * 递进工具的天然落点——届时往这里加字段即可,链路不用再改。</p>
 */
public record ToolContext(
        String taskId,         // 当前任务 id
        Path workspaceDir,     // 本任务的独立工作目录(沙箱 cwd / 挂载点),已确保存在
        String idempotencyKey, // 本次工具调用的幂等 key(= tool_call_id);任务级模板为 null,执行某工具时由 forCall 绑定
        Optional<TaskRunToken> runToken
) {

    public ToolContext {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(workspaceDir, "workspaceDir");
        Objects.requireNonNull(runToken, "runToken");
        if (runToken.isPresent() && !taskId.equals(runToken.orElseThrow().taskId())) {
            throw new IllegalArgumentException("run token taskId must match ToolContext taskId");
        }
    }

    /** 任务级模板:还没绑定到具体某次工具调用(idempotencyKey=null);真正执行某工具时用 {@link #forCall} 绑定它的 id。 */
    public ToolContext(String taskId, Path workspaceDir) {
        this(taskId, workspaceDir, null, Optional.empty());
    }

    /** Agent 运行上下文:从当前持有的 token 派生 task id。 */
    public ToolContext(TaskRunToken runToken, Path workspaceDir) {
        this(Objects.requireNonNull(runToken, "runToken").taskId(), workspaceDir,
                null, Optional.of(runToken));
    }

    /** 绑定到某一次工具调用,得到带 idempotencyKey 的上下文(供 run_command 等把 key 下推给副作用做幂等)。 */
    public ToolContext forCall(String toolCallId) {
        return new ToolContext(taskId, workspaceDir, toolCallId, runToken);
    }

    /**
     * 把模型给的(相对)路径解析到本任务工作区内,并<b>遏制</b>在工作区边界内。
     *
     * <p>解析规则:相对 {@link #workspaceDir} 解析、{@code normalize()} 折叠掉
     * {@code .}/{@code ..},再校验结果仍以 workspaceDir 为前缀。{@code ../} 往上爬、
     * 或传绝对路径({@code resolve} 遇绝对路径会直接采用它,normalize 后 startsWith 校验失败)
     * 都会被拒。</p>
     *
     * <p>为什么这道遏制必须在这层做:{@code read_file}/{@code list_dir}/{@code write_file}
     * 是<b>进程内</b>用 Java NIO 跑的(不像 {@code run_command} 走沙箱),没有这道校验,
     * 模型一个 {@code ../../etc/passwd} 或绝对路径就逃出了工作区——和 {@code run_command}
     * 辛苦锁死的"爆炸半径"自相矛盾。把文件读写也遏制在工作区内,沙箱化才算完整。</p>
     *
     * @throws WorkspaceEscapeException 路径越出工作区时抛出(消息可读,由 ToolExecutor 兜回给模型纠错)
     */
    public Path resolveInWorkspace(String path) {
        Path resolved = workspaceDir.resolve(path).normalize();
        if (!resolved.startsWith(workspaceDir)) {
            throw new WorkspaceEscapeException(path);
        }
        return resolved;
    }
}
