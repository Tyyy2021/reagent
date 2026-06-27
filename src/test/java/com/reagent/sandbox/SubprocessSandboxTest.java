package com.reagent.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubprocessSandbox} 的行为验证 —— 不依赖 Spring / LLM,纯本地、免费、可重复。
 * 重点压最值钱的那条线:<b>死循环到点被 OS 硬杀</b>,而非靠线程 interrupt(那杀不掉)。
 */
class SubprocessSandboxTest {

    private final SubprocessSandbox sandbox = new SubprocessSandbox();

    /** 默认 5s 超时、指定工作目录的 spec 起手。 */
    private SandboxSpec.Builder spec(Path dir) {
        return new SandboxSpec.Builder().workingDir(dir).timeoutMs(5_000);
    }

    @Test
    void 正常命令_成功并捕获stdout(@TempDir Path dir) {
        SandboxResult r = sandbox.run(spec(dir).command("echo hello-reagent").build());

        assertTrue(r.success(), () -> "应成功: " + r.summary());
        assertEquals(0, r.exitCode());
        assertFalse(r.killed());
        assertTrue(r.stdout().contains("hello-reagent"), r.stdout());
    }

    @Test
    void 非零退出_如实上报且不算killed(@TempDir Path dir) {
        SandboxResult r = sandbox.run(spec(dir).command("echo oops 1>&2; exit 3").build());

        assertEquals(3, r.exitCode());
        assertFalse(r.killed(), "命令自己退出,不应被标记为超时强杀");
        assertFalse(r.success());
        assertTrue(r.stderr().contains("oops"), r.stderr());
    }

    @Test
    void 死循环_到点被硬杀且及时返回(@TempDir Path dir) {
        long t0 = System.nanoTime();
        SandboxResult r = sandbox.run(
                spec(dir).command("while true; do :; done").timeoutMs(1_000).build());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(r.killed(), "死循环必须被标记为超时强杀");
        assertFalse(r.success());
        assertTrue(r.durationMs() >= 900, "应跑满超时才被杀,实际: " + r.durationMs() + "ms");
        assertTrue(elapsedMs < 9_000, "强杀必须及时返回、不能挂死,实际: " + elapsedMs + "ms");
    }

    @Test
    void 输出超限_被截断(@TempDir Path dir) {
        SandboxResult r = sandbox.run(
                spec(dir).command("seq 1 100000").maxOutputBytes(1_000).build());

        assertTrue(r.outputTruncated(), "超大输出应被截断");
        assertTrue(r.stdout().getBytes(StandardCharsets.UTF_8).length <= 1_000,
                "捕获输出不应超过上限");
    }

    @Test
    void 工作目录生效(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("marker.txt"), "MARKER-OK");

        SandboxResult r = sandbox.run(spec(dir).command("cat marker.txt").build());

        assertTrue(r.success(), r::summary);
        assertTrue(r.stdout().contains("MARKER-OK"), "应在 workingDir 下执行,实际: " + r.stdout());
    }

    @Test
    void 工作目录不存在_兜成错误而非抛异常() {
        SandboxResult r = sandbox.run(new SandboxSpec.Builder()
                .command("echo x").workingDir(Path.of("/no/such/dir-xyz-reagent")).build());

        assertEquals(-1, r.exitCode());
        assertTrue(r.startupFailed(), "工作目录不存在属基础设施故障");
        assertFalse(r.success());
    }

    // ===================== L3:完成 journal(exactly-once 对账依据)=====================

    @Test
    void 带key执行_沙箱尾部把退出码写进完成journal(@TempDir Path dir) {
        String key = "call_test_0";
        SandboxResult r = sandbox.run(spec(dir).command("echo done").idempotencyKey(key).build());

        assertTrue(r.success(), r::summary);
        assertTrue(r.stdout().contains("done"), r.stdout());
        // journal 由沙箱脚本作为【最后一步】写下:内容 = 命令真实退出码 -> 恢复时据此判"确已完成"
        assertEquals(Optional.of("0"), RunJournal.completion(dir, key),
                "成功命令应在 journal 记下退出码 0");
    }

    @Test
    void 带key非零退出_journal记真实退出码且不污染exitCode(@TempDir Path dir) {
        String key = "call_test_fail";
        SandboxResult r = sandbox.run(spec(dir).command("exit 7").idempotencyKey(key).build());

        assertEquals(7, r.exitCode(), "写 journal 后必须用命令真实退出码退出,不能被 mv/printf 步骤污染");
        assertEquals(Optional.of("7"), RunJournal.completion(dir, key));
    }

    @Test
    void 不带key执行_不写journal_行为与改造前一致(@TempDir Path dir) {
        SandboxResult r = sandbox.run(spec(dir).command("echo plain").build());

        assertTrue(r.success(), r::summary);
        assertTrue(RunJournal.completion(dir, "anything").isEmpty(), "无 key 不应产生任何 journal");
    }
}
