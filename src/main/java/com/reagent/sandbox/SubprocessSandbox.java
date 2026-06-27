package com.reagent.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 子进程沙箱 —— 用 {@code ProcessBuilder} 起一个独立子进程执行命令。
 *
 * <p>核心价值是<b>硬杀</b>:超时不靠线程 interrupt(模型生成的代码不配合检查中断就杀不掉),
 * 而是 {@code setsid} 让命令进入<b>独立进程组</b>,到点用 {@code kill -9 -<pgid>}
 * 把整组(含 fork 出来的孙进程)一起 SIGKILL;再叠加 Java 的
 * {@code descendants().destroyForcibly()} 兜底,确保杀干净不残留。</p>
 *
 * <p><b>能力边界</b>(诚实标注,留给 Docker 补全):
 * <ul>
 *   <li>内存:{@code ulimit -v} 限地址空间,<b>粗粒度</b>(限的是虚拟内存而非 RSS,对某些程序偏紧);</li>
 *   <li>CPU:子进程<b>不限</b> CPU 份额(ulimit 只能限 CPU 时间,语义不同),真限额靠 Docker 的 cgroup;</li>
 *   <li>文件系统 / 网络:<b>不隔离</b>(共享主机)——这是子进程沙箱的本质短板,Docker 才能关住。</li>
 * </ul>
 * 因此本实现定位为“硬杀可靠 + 无需 Docker、当场可跑”的兜底与对照,强隔离用 {@link SandboxType#DOCKER}。</p>
 */
@Component
public class SubprocessSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(SubprocessSandbox.class);

    /** 强杀后回收进程 / 等待读取线程收尾的宽限时间。 */
    private static final long REAP_GRACE_MS = 5_000;

    @Override
    public SandboxType type() {
        return SandboxType.SUBPROCESS;
    }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        // 工作目录必须已存在(由上层 per-task workspace 负责创建);不存在直接兜成错误结果,不抛异常。
        if (!Files.isDirectory(spec.workingDir())) {
            return infraError("工作目录不存在: " + spec.workingDir());
        }

        long startNanos = System.nanoTime();
        Process process;
        try {
            process = startProcess(spec);
        } catch (IOException e) {
            log.warn("子进程启动失败: {}", e.toString());
            return infraError("子进程启动失败: " + e.getMessage());
        }

        // 必须【并发】读 stdout/stderr:否则管道缓冲一旦写满,子进程会阻塞在 write 上、永不退出。
        StreamPump out = StreamPump.start(process.getInputStream(), spec.maxOutputBytes());
        StreamPump err = StreamPump.start(process.getErrorStream(), spec.maxOutputBytes());

        boolean killed = false;
        try {
            boolean finished = process.waitFor(spec.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                // 到点:OS 级硬杀整个进程组(这是本类存在的全部意义)
                killed = true;
                hardKill(process);
                process.waitFor(REAP_GRACE_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ie) {
            // 调用线程被外层(executor 兜底超时)中断:同样硬杀、标记 killed、恢复中断位
            killed = true;
            hardKill(process);
            Thread.currentThread().interrupt();
        }

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 等读取线程收尾,拿到(可能已截断的)输出
        String stdout = out.await();
        String stderr = err.await();
        boolean truncated = out.truncated() || err.truncated();

        int exitCode = process.isAlive() ? -1 : process.exitValue();

        SandboxResult result = new SandboxResult(exitCode, stdout, stderr, killed, false, truncated, durationMs);
        log.info("[subprocess] {} -> {}", abbreviate(spec.command()), result.summary());
        return result;
    }

    /** 用 setsid 起独立进程组;ulimit 限内存;sh -c 执行用户命令;(L3)尾部原子写完成 journal。 */
    private Process startProcess(SandboxSpec spec) throws IOException {
        // L3:有 idempotencyKey 时,把命令包成"执行 + 记完成 journal"(子shell 执行,命令里的 exit 不掀翻记账)。
        // baseDir 用宿主 workspace 绝对路径(子进程 cwd 就是它);无 key 时 wrap 原样返回命令,行为不变。
        String body = RunJournal.wrap(spec.command(), spec.workingDir().toString(), spec.idempotencyKey());
        // ulimit -v 单位 KB;2>/dev/null 容忍设置失败(某些环境不允许降限)
        String wrapped = "ulimit -v " + (spec.memoryMb() * 1024) + " 2>/dev/null; " + body;
        List<String> argv = List.of("setsid", "sh", "-c", wrapped);

        // 从 ProcessBuilder 起的 setsid 不是进程组组长 -> 不 fork,直接 setsid()+exec sh,
        // 于是 process.pid() == 新会话的 pgid,便于后面整组 kill。
        ProcessBuilder pb = new ProcessBuilder(argv).directory(spec.workingDir().toFile());
        // L3:把 idempotencyKey 注入环境变量,供未来"外部副作用 + 下游去重"的工具(类③)读取。
        if (RunJournal.validKey(spec.idempotencyKey())) {
            pb.environment().put(RunJournal.ENV_KEY, spec.idempotencyKey());
        }
        return pb.start();
        // 注:不合并 stderr 到 stdout —— 分开返回,模型能区分正常输出与报错。
        //     不做环境变量清洗(真隔离靠 Docker),保持子进程实现简单。
    }

    /** 硬杀:kill -9 整个进程组 + Java 层 destroyForcibly 兜底,确保孙进程不残留。 */
    private void hardKill(Process process) {
        long pgid = process.pid();   // setsid 未 fork 的常见情形下,pid == 新会话 pgid
        try {
            // kill -9 -<pgid>:首参为信号,后参负号表示“向整个进程组发信号”,连 fork 的孙进程一起杀
            new ProcessBuilder("kill", "-9", "-" + pgid).start().waitFor(2, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.debug("kill -9 -{} 失败,转用 destroyForcibly: {}", pgid, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 兜底:应对 setsid 罕见 fork 的情形 —— Java 层把后代进程与主进程都强杀一遍
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** 基础设施类错误(非命令本身失败,如工作目录不存在 / 子进程起不来)。 */
    private SandboxResult infraError(String msg) {
        return SandboxResult.infraError(msg, 0);
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }

    /**
     * 输出抽取器:在<b>虚拟线程</b>上把流读进内存,最多保留 {@code maxBytes} 字节,
     * 超出部分继续读但<b>丢弃</b>(防止子进程因管道写满而阻塞),并标记 {@code truncated}。
     */
    private static final class StreamPump {
        private final Thread thread;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final long maxBytes;
        private volatile boolean truncated = false;

        private StreamPump(InputStream in, long maxBytes) {
            this.maxBytes = maxBytes;
            this.thread = Thread.ofVirtual().unstarted(() -> pump(in));
        }

        static StreamPump start(InputStream in, long maxBytes) {
            StreamPump p = new StreamPump(in, maxBytes);
            p.thread.start();
            return p;
        }

        private void pump(InputStream in) {
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    long room = maxBytes - buf.size();
                    if (room > 0) {
                        int take = (int) Math.min(room, n);
                        buf.write(chunk, 0, take);
                        if (take < n) truncated = true;
                    } else {
                        truncated = true; // 已满:继续读并丢弃,只为不让子进程阻塞在 write 上
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时流被关,read 抛异常属正常,忽略
            }
        }

        String await() {
            try {
                thread.join(REAP_GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return buf.toString(StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }
    }
}
