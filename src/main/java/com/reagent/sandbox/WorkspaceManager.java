package com.reagent.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 每任务工作区管理:把 taskId 解析成 {@code workspaceRoot/<taskId>} 目录并确保其存在。
 *
 * <p>这是沙箱“文件系统爆炸半径”设计的落点 —— 每个任务在独立子目录里干活:
 * {@code run_command} 的 {@code rm -rf} 顶多毁掉本任务这份副本,删不到真实代码、
 * 也不与其它任务相互踩;目录按 taskId 命名 + 放稳定根目录下,崩溃续跑时还能找回同一个工作区。</p>
 */
@Component
public class WorkspaceManager {

    private final Path root;

    public WorkspaceManager(SandboxProperties props) {
        this.root = Path.of(props.getWorkspaceRoot());
    }

    /** 解析并(幂等地)创建本任务的工作目录,返回其绝对路径。 */
    public Path workspaceFor(String taskId) {
        Path dir = root.resolve(taskId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 建不出工作目录属基础设施故障,直接抛(让上层按任务异常处理),不静默吞掉
            throw new UncheckedIOException("无法创建任务工作目录: " + dir, e);
        }
        return dir.toAbsolutePath();
    }
}
