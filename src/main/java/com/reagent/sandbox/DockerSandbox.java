package com.reagent.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 容器沙箱 —— 用 docker-java <b>编程式</b>起一个<b>一次性容器</b>执行命令,提供最强隔离:
 * <ul>
 *   <li><b>cgroup 限额</b>:{@code --memory} / {@code --cpus}(真正限 RSS 与 CPU 份额,
 *       子进程的 ulimit 做不到);</li>
 *   <li><b>断网</b>:{@code --network=none}(命令偷不了数据、连不上外网);</li>
 *   <li><b>文件系统隔离</b>:容器独立 rootfs,只把本任务 workspace 挂到 {@code /workspace};</li>
 *   <li><b>硬杀</b>:到点 {@code docker kill} 整个容器(比杀进程更彻底)。</li>
 * </ul>
 *
 * <p>每次 run 起一个新容器、跑完即删({@code --rm} 语义)。镜像<b>懒加载</b>:首次发现本地
 * 没有该镜像时拉一次(需要网络),之后容器一律 {@code --network=none} 离线跑。</p>
 *
 * <p>DockerClient 较重且依赖 daemon,故<b>懒加载并复用</b>——subprocess 模式下根本不建,
 * 没装/没开 Docker 也不影响应用启动与子进程沙箱。</p>
 */
@Component
public class DockerSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);
    private static final String WORKDIR = "/workspace";
    private static final long PULL_TIMEOUT_MIN = 5;
    private static final int KILLED_EXIT_CODE = 137; // 128 + SIGKILL(9)

    private final SandboxProperties props;

    private volatile DockerClient client;        // 懒加载
    private volatile boolean imageEnsured = false;

    public DockerSandbox(SandboxProperties props) {
        this.props = props;
    }

    @Override
    public SandboxType type() {
        return SandboxType.DOCKER;
    }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        long startNanos = System.nanoTime();

        final DockerClient docker;
        try {
            docker = client();
            ensureImage(docker);
        } catch (Exception e) {
            // 连不上 daemon / 拉不到镜像 —— 基础设施故障,告诉模型“别改命令重试”
            log.warn("Docker 基础设施不可用: {}", e.toString());
            return SandboxResult.infraError("Docker 不可用(daemon 或镜像): " + e.getMessage(),
                    elapsedMs(startNanos));
        }

        String containerId = null;
        try {
            containerId = createContainer(docker, spec);

            // 输出收集回调(边收边截断),随后启动容器
            OutputCollector collector = new OutputCollector(spec.maxOutputBytes());
            docker.startContainerCmd(containerId).exec();
            docker.logContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true).withFollowStream(true).withTailAll()
                    .exec(collector);

            // 等待 + 超时硬杀
            boolean killed = false;
            int exitCode;
            try {
                exitCode = docker.waitContainerCmd(containerId)
                        .exec(new WaitContainerResultCallback())
                        .awaitStatusCode(spec.timeoutMs(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException timeoutOrErr) {
                // awaitStatusCode 超时会抛 DockerClientException —— docker kill 整个容器
                killed = true;
                safeKill(docker, containerId);
                exitCode = inspectExitCode(docker, containerId);
            }

            collector.awaitClose();
            SandboxResult result = new SandboxResult(exitCode, collector.stdout(), collector.stderr(),
                    killed, false, collector.truncated(), elapsedMs(startNanos));
            log.info("[docker] {} -> {}", abbreviate(spec.command()), result.summary());
            return result;

        } catch (Exception e) {
            log.warn("Docker 执行异常: {}", e.toString());
            return SandboxResult.infraError("Docker 执行异常: " + e.getMessage(), elapsedMs(startNanos));
        } finally {
            if (containerId != null) {
                safeRemove(docker, containerId);  // --rm 语义:跑完即删,不留垃圾容器
            }
        }
    }

    /** 创建容器:cgroup 限额 + 网络模式 + 挂载本任务 workspace 到 /workspace + cwd。 */
    private String createContainer(DockerClient docker, SandboxSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(spec.memoryMb() * 1024 * 1024)
                .withNanoCPUs((long) (spec.cpus() * 1_000_000_000L))
                .withNetworkMode(spec.networkEnabled() ? "bridge" : "none")
                .withBinds(new Bind(spec.workingDir().toString(), new Volume(WORKDIR)));

        // L3:有 idempotencyKey 时把命令包成"执行 + 记完成 journal"(子shell 执行,命令里的 exit 不掀翻记账)。
        // baseDir 用容器内挂载点 /workspace(绑定到宿主 workspace,文件最终落宿主同一处,恢复时 AgentRunner 读得到)。
        String cmd = RunJournal.wrap(spec.command(), WORKDIR, spec.idempotencyKey());

        var create = docker.createContainerCmd(props.getDocker().getImage())
                .withCmd("sh", "-c", cmd)
                .withHostConfig(hostConfig)
                .withWorkingDir(WORKDIR);
        // L3:把 idempotencyKey 注入容器环境变量(类③外部去重铺路)。
        if (RunJournal.validKey(spec.idempotencyKey())) {
            create = create.withEnv(RunJournal.ENV_KEY + "=" + spec.idempotencyKey());
        }
        CreateContainerResponse created = create.exec();
        return created.getId();
    }

    // ===== DockerClient / 镜像 懒加载 =====

    private DockerClient client() {
        DockerClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = buildClient();
                    client = c;
                }
            }
        }
        return c;
    }

    private DockerClient buildClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    /** 确保镜像在本地:没有就拉一次(需要网络)。拉过一次后置位,后续 run 直接跳过。 */
    private void ensureImage(DockerClient docker) throws InterruptedException {
        if (imageEnsured) {
            return;
        }
        synchronized (this) {
            if (imageEnsured) {
                return;
            }
            String image = props.getDocker().getImage();
            try {
                docker.inspectImageCmd(image).exec();   // 本地已有
            } catch (NotFoundException notFound) {
                log.info("本地无镜像 {},开始拉取(首次较慢)...", image);
                docker.pullImageCmd(image)
                        .exec(new ResultCallback.Adapter<PullResponseItem>())
                        .awaitCompletion(PULL_TIMEOUT_MIN, TimeUnit.MINUTES);
                log.info("镜像 {} 拉取完成", image);
            }
            imageEnsured = true;
        }
    }

    // ===== 清理 / 探测,均吞掉异常以免遮蔽主流程结果 =====

    private void safeKill(DockerClient docker, String containerId) {
        try {
            docker.killContainerCmd(containerId).exec();
        } catch (RuntimeException e) {
            log.debug("kill 容器 {} 失败(可能已退出): {}", shortId(containerId), e.toString());
        }
    }

    private void safeRemove(DockerClient docker, String containerId) {
        try {
            docker.removeContainerCmd(containerId).withForce(true).exec();
        } catch (RuntimeException e) {
            log.debug("删除容器 {} 失败: {}", shortId(containerId), e.toString());
        }
    }

    private int inspectExitCode(DockerClient docker, String containerId) {
        try {
            Long code = docker.inspectContainerCmd(containerId).exec().getState().getExitCodeLong();
            return code != null ? code.intValue() : KILLED_EXIT_CODE;
        } catch (RuntimeException e) {
            return KILLED_EXIT_CODE;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(12, id.length()));
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }

    /**
     * 容器日志回调:docker-java 在自己的线程上把 stdout/stderr 的 {@link Frame} 推过来,
     * 这里按流分别累积、各自截断到 maxBytes。
     */
    private static final class OutputCollector extends ResultCallback.Adapter<Frame> {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final long maxBytes;
        private volatile boolean truncated = false;

        OutputCollector(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            if (payload == null || payload.length == 0) {
                return;
            }
            ByteArrayOutputStream target = (frame.getStreamType() == StreamType.STDERR) ? err : out;
            long room = maxBytes - target.size();
            if (room <= 0) {
                truncated = true;
                return;
            }
            int take = (int) Math.min(room, payload.length);
            target.write(payload, 0, take);
            if (take < payload.length) {
                truncated = true;
            }
        }

        void awaitClose() {
            try {
                awaitCompletion(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String stdout() { return out.toString(StandardCharsets.UTF_8); }
        String stderr() { return err.toString(StandardCharsets.UTF_8); }
        boolean truncated() { return truncated; }
    }
}
