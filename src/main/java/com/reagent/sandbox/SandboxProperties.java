package com.reagent.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱配置,对应 application.yml 的 {@code reagent.sandbox.*}(M3 新增)。
 * 通过主类的 {@code @ConfigurationPropertiesScan} 自动注册,无需额外 {@code @Component}。
 */
@ConfigurationProperties(prefix = "reagent.sandbox")
public class SandboxProperties {

    /** 用哪种沙箱:SUBPROCESS(默认,无需 Docker、当场可跑) / DOCKER(隔离最强)。 */
    private SandboxType type = SandboxType.SUBPROCESS;

    /** 硬超时(毫秒):命令跑超过此值则被 OS 级强杀。 */
    private long timeoutMs = 30_000;

    /** 内存上限(MB)。 */
    private long memoryMb = 256;

    /** CPU 上限(核数,可小数,如 0.5)。 */
    private double cpus = 1.0;

    /** 是否允许沙箱内命令联网(默认否——断网更安全,也便于演示隔离)。 */
    private boolean networkEnabled = false;

    /** stdout/stderr 各自的截断上限(字节),防止超大输出撑爆内存 / 上下文。 */
    private long maxOutputBytes = 64 * 1024;

    /**
     * 每任务工作区的宿主根目录。每个 task 在其下有独立子目录(按 taskId),
     * 作沙箱 cwd / Docker 挂载点——把 {@code rm -rf} 的爆炸半径锁死在该任务的副本里:
     * 删不到真实代码、也不与其它任务相互踩。请放<b>稳定目录(非 /tmp)</b>,
     * 以便崩溃续跑时能找回同一个工作区。
     */
    private String workspaceRoot = "/var/reagent/workspaces";

    /** Docker 实现专用配置(SUBPROCESS 模式下忽略)。 */
    private Docker docker = new Docker();

    /** Docker 沙箱专属参数。 */
    public static class Docker {
        /** 跑命令所用镜像(需自带 sh;Coding Agent 跑 Python 用 {@code python:3.x-slim})。 */
        private String image = "python:3.12-slim";

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
    }

    // ===== getters / setters =====
    public SandboxType getType() { return type; }
    public void setType(SandboxType type) { this.type = type; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public long getMemoryMb() { return memoryMb; }
    public void setMemoryMb(long memoryMb) { this.memoryMb = memoryMb; }

    public double getCpus() { return cpus; }
    public void setCpus(double cpus) { this.cpus = cpus; }

    public boolean isNetworkEnabled() { return networkEnabled; }
    public void setNetworkEnabled(boolean networkEnabled) { this.networkEnabled = networkEnabled; }

    public long getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(long maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }

    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }

    public Docker getDocker() { return docker; }
    public void setDocker(Docker docker) { this.docker = docker; }
}
