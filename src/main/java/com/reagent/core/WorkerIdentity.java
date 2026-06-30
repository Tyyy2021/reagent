package com.reagent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * ★ M7:本进程(worker)的稳定身份。
 *
 * <p>分布式可恢复的前提是"每个 worker 有个稳定且唯一的 id":租约(lease)记在 {@code task.owner_id}
 * 上,据此区分"任务此刻在谁手里"。这个 id 要满足两点——</p>
 * <ul>
 *   <li><b>实例间唯一</b>:否则两个进程会互相抢占/覆盖对方的租约;</li>
 *   <li><b>重启后稳定</b>:同一实例崩溃重启后仍是同一 id,才能开机【秒认领】自己崩前持有的任务
 *       (见 {@code TaskRepository.claim} 里的 {@code owner_id = :me} 一支),不必干等租约 TTL 过期。</li>
 * </ul>
 *
 * <p>默认 id = {@code 主机名:端口}:同机多实例靠端口区分(本地双 worker demo 跑 8080 / 8081),
 * 且端口对一个实例是固定的 → 重启稳定。k8s 等环境可用 {@code reagent.worker.id}(填 Pod 名)显式覆盖。</p>
 */
@Component
public class WorkerIdentity {

    private static final Logger log = LoggerFactory.getLogger(WorkerIdentity.class);

    private final String id;

    public WorkerIdentity(@Value("${reagent.worker.id:}") String configured,
                          @Value("${server.port:8080}") String port) {
        if (configured != null && !configured.isBlank()) {
            this.id = configured.trim();
        } else {
            this.id = hostname() + ":" + port;
        }
        log.info("worker 身份 = {}", this.id);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    /** 本 worker 的稳定唯一 id(租约 owner、span 属性都用它)。 */
    public String id() {
        return id;
    }
}
