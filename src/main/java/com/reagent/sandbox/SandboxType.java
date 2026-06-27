package com.reagent.sandbox;

/**
 * 沙箱实现的种类。用配置 {@code reagent.sandbox.type} 选择;
 * 也方便日志区分、以及现场做“子进程 vs 容器”的隔离对照演示。
 */
public enum SandboxType {

    /**
     * 子进程沙箱:{@code ProcessBuilder} 起子进程,{@code setsid} 开独立进程组、
     * {@code destroyForcibly()} 连进程组一起硬杀,{@code ulimit} 限资源。
     * 隔离强度【中】(仍共享主机文件系统与网络),但无需 Docker、当场可跑,
     * 也作为 Docker 不可用时的兜底与对照演示。
     */
    SUBPROCESS,

    /**
     * 容器沙箱:docker-java 起一次性容器,cgroup 限额({@code --memory}/{@code --cpus})、
     * 断网({@code --network=none})、独立 rootfs + 只挂载本任务工作区。
     * 隔离强度【高】,是 M3 的主力亮点。
     */
    DOCKER
}
