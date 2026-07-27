package com.reagent.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DockerSandbox} 集成测试 —— 验证容器隔离的几条核心保证:
 * <b>硬杀(容器 kill) / 断网(--network=none) / 文件隔离(独立 rootfs + workspace 挂载)</b>。
 *
 * <p>需要本机 Docker daemon 可用 + {@code python:3.12-slim} 镜像;不满足时测试明确失败。
 * 不依赖 LLM,免费可重复。</p>
 */
class DockerSandboxIT {

    private final DockerSandbox sandbox = new DockerSandbox(new SandboxProperties());

    private SandboxSpec.Builder spec(Path dir) {
        return new SandboxSpec.Builder().workingDir(dir).timeoutMs(20_000);
    }

    /** 跑个最简命令探测 Docker 是否可用;startupFailed 说明 daemon/镜像不可用,明确失败。 */
    private void assertDockerReady(Path dir) {
        SandboxResult probe = sandbox.run(spec(dir).command("echo probe").build());
        assertFalse(probe.startupFailed(), "Docker 不可用:" + probe.stderr());
    }

    @Test
    void 正常命令_成功并捕获输出(@TempDir Path dir) {
        assertDockerReady(dir);
        SandboxResult r = sandbox.run(spec(dir).command("echo hello-docker").build());
        assertTrue(r.success(), () -> "应成功: " + r.summary() + " stderr=" + r.stderr());
        assertTrue(r.stdout().contains("hello-docker"), r.stdout());
    }

    @Test
    void 死循环_到点被容器kill(@TempDir Path dir) {
        assertDockerReady(dir);
        long t0 = System.nanoTime();
        SandboxResult r = sandbox.run(
                spec(dir).command("while true; do :; done").timeoutMs(2_000).build());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(r.killed(), "死循环必须被容器 kill");
        assertFalse(r.success());
        assertTrue(elapsedMs < 20_000, "应及时返回,实际: " + elapsedMs + "ms");
    }

    @Test
    void 断网_networkNone下连不上外网(@TempDir Path dir) {
        assertDockerReady(dir);
        // --network=none 下没有外网接口,访问外网应失败(命令自身非零退出,而非超时被杀)
        SandboxResult r = sandbox.run(spec(dir).command(
                "python3 -c \"import urllib.request; urllib.request.urlopen('http://example.com', timeout=4)\"")
                .build());

        assertFalse(r.success(), "断网下访问外网应失败");
        assertFalse(r.killed(), "应是命令自身失败(连不上),而非超时被杀");
    }

    @Test
    void 文件隔离_workspace挂载生效且rootfs独立(@TempDir Path dir) throws Exception {
        assertDockerReady(dir);

        // 1) 容器里往 /workspace 写文件 -> 宿主 workspace 应能看到(挂载生效)
        SandboxResult w = sandbox.run(spec(dir).command("echo docker-fs-ok > out.txt").build());
        assertTrue(w.success(), w::summary);
        assertEquals("docker-fs-ok", Files.readString(dir.resolve("out.txt")).trim());

        // 2) 容器根文件系统独立:宿主的 /var/reagent 在容器里看不到
        SandboxResult ls = sandbox.run(spec(dir).command(
                "ls /var 2>/dev/null | grep -q reagent && echo SEES_HOST || echo NO_HOST_FS").build());
        assertTrue(ls.stdout().contains("NO_HOST_FS"),
                "容器内不应看到宿主的 /var/reagent,实际: " + ls.stdout());
    }
}
