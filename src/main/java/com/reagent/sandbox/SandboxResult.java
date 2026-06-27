package com.reagent.sandbox;

/**
 * 一次沙箱执行的结果。<b>纯数据载体</b>——本身不抛异常,所有“失败”都编码进字段里
 * (非零退出码、被强杀、被截断、基础设施故障……),由上层 {@code RunCommandTool} 格式化成给模型看的文本。
 */
public record SandboxResult(
        int exitCode,            // 退出码;被 SIGKILL 强杀时 Linux 下通常为 137(128+9)
        String stdout,           // 标准输出(可能已按 maxOutputBytes 截断)
        String stderr,           // 标准错误(可能已截断);startupFailed 时这里放基础设施错误信息
        boolean killed,          // 是否因【超时被强杀】(区别于命令自己非零退出)
        boolean startupFailed,   // 是否【基础设施故障】:沙箱根本没能把命令跑起来
                                 //   (如 Docker daemon 不可用 / 镜像拉取失败 / 工作目录建不出)。
                                 //   ——这与“命令跑起来了但失败”是两回事:模型据此判断“别改命令重试”。
        boolean outputTruncated, // 输出是否因超过上限被截断
        long durationMs          // 实际耗时(毫秒)
) {
    /** 命令是否“干净成功”:正常退出 0、未被强杀、也没有基础设施故障。 */
    public boolean success() {
        return exitCode == 0 && !killed && !startupFailed;
    }

    /** 给日志用的一行摘要,不含可能很长的 stdout/stderr。 */
    public String summary() {
        return "exit=" + exitCode
                + (killed ? " KILLED(timeout)" : "")
                + (startupFailed ? " STARTUP_FAILED" : "")
                + (outputTruncated ? " TRUNCATED" : "")
                + " in " + durationMs + "ms";
    }

    /** 便捷构造:超时被强杀的结果。exitCode 由实现层据实填(通常 137)。 */
    public static SandboxResult killedByTimeout(int exitCode, String stdout, String stderr,
                                                boolean truncated, long durationMs) {
        return new SandboxResult(exitCode, stdout, stderr, true, false, truncated, durationMs);
    }

    /** 便捷构造:基础设施故障(沙箱没能把命令跑起来),信息放 stderr。 */
    public static SandboxResult infraError(String message, long durationMs) {
        return new SandboxResult(-1, "", message, false, true, false, durationMs);
    }
}
