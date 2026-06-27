package com.reagent.sandbox;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次沙箱执行的“输入规格”——要跑什么、在哪跑、给多少资源、是否联网。
 *
 * <p>刻意设计成<b>与具体实现无关</b>:这里只描述“想要的约束”,
 * 由 {@code SubprocessSandbox} / {@code DockerSandbox} 各自翻译成
 * {@code ulimit}/{@code setsid} 或 {@code --memory}/{@code --cpus}/{@code --network=none}。</p>
 *
 * <p>{@link #command} 是一段 shell 命令字符串,实现会用 {@code sh -c "<command>"} 执行——
 * 灵活、对 Coding Agent 友好;<b>安全不靠命令白名单,而靠沙箱的隔离与硬杀兜底</b>。</p>
 *
 * <p>各字段默认值来自 {@link SandboxProperties};调用方(工具)用 {@link Builder}
 * 从默认起手、只覆盖关心的字段(通常是 {@code command} 与 {@code workingDir})。</p>
 */
public record SandboxSpec(
        String command,         // 要执行的 shell 命令,如 "ls -la" 或 "python -c '...'"
        Path workingDir,        // 工作目录(每任务独立 workspace),作 cwd / 容器挂载点
        long timeoutMs,         // 硬超时:超过则 OS 级强杀
        long memoryMb,          // 内存上限(MB)
        double cpus,            // CPU 上限(核数,可小数,如 0.5)
        boolean networkEnabled, // 是否允许联网(默认否)
        long maxOutputBytes,    // stdout / stderr 各自的截断上限(字节),防输出爆内存
        String idempotencyKey   // 本次调用的幂等 key(= tool_call_id);非空时沙箱写完成 journal + 注入 env,供 L3 对账。可空
) {
    /** 紧凑构造器:做参数校验与基本约束,保证传到实现层的 spec 一定是合法的。 */
    public SandboxSpec {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("sandbox command 不能为空");
        }
        Objects.requireNonNull(workingDir, "sandbox workingDir 不能为空");
        if (timeoutMs <= 0)      throw new IllegalArgumentException("timeoutMs 必须为正: " + timeoutMs);
        if (memoryMb <= 0)       throw new IllegalArgumentException("memoryMb 必须为正: " + memoryMb);
        if (cpus <= 0)           throw new IllegalArgumentException("cpus 必须为正: " + cpus);
        if (maxOutputBytes <= 0) throw new IllegalArgumentException("maxOutputBytes 必须为正: " + maxOutputBytes);
    }

    /**
     * 以 {@link SandboxProperties} 的默认值起一个 {@link Builder}。
     * 调用方通常只需再 {@code .command(...).workingDir(...)} 即可 build。
     */
    public static Builder fromDefaults(SandboxProperties props) {
        return new Builder()
                .timeoutMs(props.getTimeoutMs())
                .memoryMb(props.getMemoryMb())
                .cpus(props.getCpus())
                .networkEnabled(props.isNetworkEnabled())
                .maxOutputBytes(props.getMaxOutputBytes());
    }

    /** 因为字段多、且多数有默认值,用 Builder 比层层叠叠的构造器更可读、更难传错位。 */
    public static final class Builder {
        private String command;
        private Path workingDir;
        private long timeoutMs = 30_000;
        private long memoryMb = 256;
        private double cpus = 1.0;
        private boolean networkEnabled = false;
        private long maxOutputBytes = 64 * 1024;
        private String idempotencyKey;   // 默认 null = 不写 journal / 不注入 env(沙箱单测、无副作用场景)

        public Builder command(String command)            { this.command = command; return this; }
        public Builder workingDir(Path workingDir)         { this.workingDir = workingDir; return this; }
        public Builder timeoutMs(long timeoutMs)           { this.timeoutMs = timeoutMs; return this; }
        public Builder memoryMb(long memoryMb)             { this.memoryMb = memoryMb; return this; }
        public Builder cpus(double cpus)                   { this.cpus = cpus; return this; }
        public Builder networkEnabled(boolean enabled)     { this.networkEnabled = enabled; return this; }
        public Builder maxOutputBytes(long maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; return this; }
        public Builder idempotencyKey(String key)          { this.idempotencyKey = key; return this; }

        public SandboxSpec build() {
            return new SandboxSpec(command, workingDir, timeoutMs, memoryMb, cpus, networkEnabled, maxOutputBytes, idempotencyKey);
        }
    }
}
