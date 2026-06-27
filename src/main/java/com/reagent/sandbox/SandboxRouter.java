package com.reagent.sandbox;

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

    public SandboxRouter(SubprocessSandbox subprocess, DockerSandbox docker, SandboxProperties props) {
        this.subprocess = subprocess;
        this.docker = docker;
        this.props = props;
        log.info("沙箱路由就绪,当前类型: {}", props.getType());
    }

    /** 每次按当前配置取实现(读字段,便于将来支持运行时切换)。 */
    private Sandbox delegate() {
        return props.getType() == SandboxType.DOCKER ? docker : subprocess;
    }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        return delegate().run(spec);
    }

    @Override
    public SandboxType type() {
        return props.getType();
    }
}
