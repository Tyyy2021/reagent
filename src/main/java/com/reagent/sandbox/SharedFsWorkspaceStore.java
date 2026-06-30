package com.reagent.sandbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link WorkspaceStore} 的<b>共享文件系统</b>实现(M7 C,默认)。
 *
 * <p>每任务一个 {@code workspaceRoot/<taskId>} 目录(沿用原 WorkspaceManager 逻辑):{@code run_command} 的
 * {@code rm -rf} 顶多毁掉本任务这份副本、删不到真实代码也不踩别的任务;目录按 taskId 命名 + 放稳定根目录,崩溃续跑找回同一个。</p>
 *
 * <p><b>跨 worker(M7):</b>把 workspace-root 指向一块所有 worker 共享挂载的盘(本地 demo = 同机同路径天然共享;
 * 生产 = NFS / EFS / PVC),失败转移后接管 worker {@link #checkout} 到的就是原 worker 写的同一份,故 {@link #commit}
 * 无需做事。要去掉共享盘依赖,换 git / 对象存储实现即可(本接口已留缝)。</p>
 */
@Component
@ConditionalOnProperty(name = "reagent.workspace.store", havingValue = "shared", matchIfMissing = true)
public class SharedFsWorkspaceStore implements WorkspaceStore {

    private final Path root;

    public SharedFsWorkspaceStore(SandboxProperties props) {
        this.root = Path.of(props.getWorkspaceRoot());
    }

    @Override
    public Path checkout(String taskId) {
        Path dir = root.resolve(taskId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 建不出工作目录属基础设施故障,直接抛(让上层按任务异常处理),不静默吞掉
            throw new UncheckedIOException("无法创建任务工作目录: " + dir, e);
        }
        return dir.toAbsolutePath();
    }

    @Override
    public void commit(String taskId) {
        // no-op:shared-fs 下所有 worker 挂同一共享盘到 workspace-root,写入实时互见,无需显式同步。
    }
}
