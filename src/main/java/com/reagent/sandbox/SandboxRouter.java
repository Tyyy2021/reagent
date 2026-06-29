package com.reagent.sandbox;

import com.reagent.obs.Trace;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 沙箱路由:按配置 {@code reagent.sandbox.type} 在 subprocess / docker 之间选实现。
 *
 * <p>标 {@code @Primary},所以 {@code RunCommandTool} 注入 {@link Sandbox} 时拿到的是本路由——
 * 底层用哪种沙箱由配置切换,改配置即可做“子进程 vs 容器”的隔离对照演示,工具与上层零改动。</p>
 */
@Component
@Primary
public class SandboxRouter implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(SandboxRouter.class);

    private final SubprocessSandbox subprocess;
    private final DockerSandbox docker;
    private final SandboxProperties props;
    private final Tracer tracer;

    public SandboxRouter(SubprocessSandbox subprocess, DockerSandbox docker, SandboxProperties props, Tracer tracer) {
        this.subprocess = subprocess;
        this.docker = docker;
        this.props = props;
        this.tracer = tracer;
        log.info("沙箱路由就绪,当前类型: {}", props.getType());
    }

    /** 每次按当前配置取实现(读字段,便于将来支持运行时切换)。 */
    private Sandbox delegate() {
        return props.getType() == SandboxType.DOCKER ? docker : subprocess;
    }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        // M6:沙箱执行 span(parent = 当前 execute_tool span;沙箱同步同线程,自动成其子)。在 @Primary 单一入口埋,
        // 统一标 sandbox.type、不侵入 subprocess/docker 两实现。
        Span span = tracer.spanBuilder("sandbox.run")
                .setAttribute(Trace.SANDBOX_TYPE, props.getType().name())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            SandboxResult r = delegate().run(spec);
            span.setAttribute(Trace.EXIT_CODE, (long) r.exitCode());
            span.setAttribute(Trace.KILLED, r.killed());
            span.setAttribute(Trace.STARTUP_FAILED, r.startupFailed());
            span.setAttribute(Trace.DURATION_MS, r.durationMs());
            if (r.startupFailed()) {
                span.setStatus(StatusCode.ERROR, "sandbox startup failed");
            } else if (r.killed()) {
                span.setStatus(StatusCode.ERROR, "killed by timeout");
            }
            return r;
        } finally {
            span.end();
        }
    }

    @Override
    public SandboxType type() {
        return props.getType();
    }
}
