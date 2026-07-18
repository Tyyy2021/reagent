package com.reagent.api;

import com.reagent.core.AgentRunner;
import com.reagent.core.TaskControl;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskStatus;
import com.reagent.stream.TaskEvent;
import com.reagent.stream.StreamTransport;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对外入口。
 *
 * M1 只有"提交并同步跑完"。M2 加了任务 id 与状态查询、以及手动恢复入口。
 * M4 把提交改成<b>异步 + SSE 流式</b>:POST 立即返回 taskId,过程经 {@code GET /{id}/stream} 实时推;
 * 老的同步行为保留为 {@code ?sync=true} 兜底(e2e / 快测友好)。任务与连接解耦——断开/重连不影响执行。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final AgentRunner runner;
    private final StateStore stateStore;
    private final StreamTransport bus;
    private final TaskControl taskControl;

    public TaskController(AgentRunner runner, StateStore stateStore, StreamTransport bus, TaskControl taskControl) {
        this.runner = runner;
        this.stateStore = stateStore;
        this.bus = bus;
        this.taskControl = taskControl;
    }

    /**
     * 提交目标。
     * 默认<b>异步</b>:立即返回 {@code {taskId,status:"RUNNING"}}(202),用 {@code GET /{id}/stream} 看过程。
     * {@code ?sync=true}:走老的<b>阻塞</b>路径,跑完返回 {@code {taskId,goal,result}}。
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@RequestBody TaskRequest request,
                                                      @RequestParam(defaultValue = "false") boolean sync) {
        String profile = defaultProfile(request.profile());
        if (sync) {
            AgentRunner.RunResult r = runner.run(request.goal(), profile);
            return ResponseEntity.ok(Map.of(
                    "taskId", r.taskId(), "goal", request.goal(), "result", r.result()));
        }
        String taskId = runner.submit(request.goal(), profile);
        return ResponseEntity.accepted().body(Map.of("taskId", taskId, "status", "RUNNING"));
    }

    /**
     * 订阅某任务的事件流(SSE),支持<b>断点续播</b>(M4 Stage4)。
     *
     * <p>可带 {@code Last-Event-ID} 头(浏览器 EventSource 重连自动带、curl 用 {@code -H} 指定):服务端先从
     * {@code event} 表补播 id 大于该游标的历史事件、再无缝转 live —— 补播与 live 经【同一个 sink】发出、
     * 完全同构、不漏不重(in-process 由每任务锁、redis 由 XREAD-from-cursor 保证,见各 {@link StreamTransport} 实现)。缺省 / 脏值游标 = 从头补播。</p>
     *
     * <p>已终态任务:补播里就含终态事件,sink 发完即收尾——不再需要单发合成快照。SseEmitter 超时(1h)只断开
     * "视图",任务在虚拟线程上照跑,这正是执行 / 连接解耦。</p>
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        stateStore.getTask(id);                          // 不存在 -> IllegalArgumentException(避免给瞎 id 建流)
        // opaque 游标:规整成非 null 串传给 transport 自解释(in-process=event 表 id,redis=stream id);缺省/空=从头
        String cursor = (lastEventId == null || lastEventId.isBlank()) ? "0" : lastEventId.trim();
        SseEmitter emitter = new SseEmitter(3_600_000L);
        AtomicBoolean done = new AtomicBoolean(false);   // 防终态事件重复收尾

        // 单一 sink:补播(从 event 表重建的历史事件)与 live(实时事件)走完全相同的发送路径
        StreamTransport.EventSink sink = event -> sendEvent(emitter, event, done);

        // HELLO:立即冲响应头让客户端确认连上;不带 id,不影响 Last-Event-ID 游标
        try {
            emitter.send(SseEmitter.event().name("HELLO")
                    .data(Map.of("taskId", id, "fromEventId", cursor), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // 原子地:补播 id>cursor 的历史 -> 挂 live sink(期间 publish 被同一把每任务锁挡住 = 不漏不重)
        StreamTransport.Subscription sub = bus.subscribeWithReplay(id, cursor, sink);
        if (done.get()) {
            sub.close();   // 补播里已含终态、emitter 已收尾:立即退订,不留悬挂订阅
        } else {
            emitter.onCompletion(sub::close);
            emitter.onTimeout(() -> { sub.close(); emitter.complete(); });
            emitter.onError(e -> sub.close());
        }
        return emitter;
    }

    /** 查任务状态:{ taskId, status, goal, result } */
    @GetMapping("/{id}")
    public Map<String, String> status(@PathVariable String id) {
        TaskEntity t = stateStore.getTask(id);
        return Map.of(
                "taskId", t.getId(),
                "status", t.getStatus().name(),
                "goal", t.getGoal(),
                "result", t.getResult() == null ? "" : t.getResult(),
                "profile", t.getProfileId() == null ? "" : t.getProfileId());
    }

    /** M4:取消任务。默认优雅(当前工具跑完后于安全点停);?force=true 硬杀(Stage3b)。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable String id,
                                                      @RequestParam(defaultValue = "false") boolean force) {
        // M7 Stage4:先试本地(任务恰在本 worker 驱动)—— 立即生效,force 还能硬杀 in-flight 工具
        if (taskControl.requestCancel(id, force)) {
            return ResponseEntity.accepted().body(Map.of("taskId", id,
                    "message", force ? "已请求取消(本地 force 硬杀)" : "已请求取消(本地优雅:当前工具跑完后停)"));
        }
        // 本地没有 → 任务在别的 worker 上:落 DB 控制信号,由其 owner 在安全点优雅消费(位置透明)
        if (stateStore.requestControl(id, "CANCEL")) {
            return ResponseEntity.accepted().body(Map.of("taskId", id,
                    "message", "已请求取消(跨 worker:owner 将在安全点优雅停"
                            + (force ? ";force 跨 worker 退化为优雅)" : ")")));
        }
        return ResponseEntity.status(409).body(Map.of(
                "taskId", id, "message", "任务不在运行中(可能已结束),无法取消"));
    }

    /** M4:暂停任务(优雅,留下可 resume 的干净状态)。 */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, String>> pause(@PathVariable String id) {
        // M7 Stage4:先试本地,本地没有再落 DB 信号(跨 worker 优雅暂停)
        if (taskControl.requestPause(id)) {
            return ResponseEntity.accepted().body(Map.of("taskId", id,
                    "message", "已请求暂停(本地:当前工具跑完后停)"));
        }
        if (stateStore.requestControl(id, "PAUSE")) {
            return ResponseEntity.accepted().body(Map.of("taskId", id,
                    "message", "已请求暂停(跨 worker:owner 将在安全点停)"));
        }
        return ResponseEntity.status(409).body(Map.of(
                "taskId", id, "message", "任务不在运行中,无法暂停"));
    }

    /** 续跑一个 PAUSED 任务(异步,过程经 /stream 跟)。RUNNING 也放行(InFlightTasks 挡双驱动);终态拒绝。 */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, String>> resume(@PathVariable String id) {
        TaskEntity t = stateStore.getTask(id);
        if (t.getStatus() != TaskStatus.PAUSED && t.getStatus() != TaskStatus.RUNNING) {
            return ResponseEntity.status(409).body(Map.of("taskId", id,
                    "status", t.getStatus().name(), "message", "仅 PAUSED 任务可续跑"));
        }
        runner.resumeAsync(id);
        return ResponseEntity.accepted().body(Map.of("taskId", id,
                "status", "RUNNING", "message", "已异步续跑,可用 /stream 跟进"));
    }

    /**
     * 把一个事件发到 SSE 连接。成功返回 {@code true};客户端断开 / IO 异常返回 {@code false}
     * (供 {@link TaskEventBus} 摘除该失效订阅 / 停止补播)。补播与 live 共用本方法 = 同构。
     */
    private boolean sendEvent(SseEmitter emitter, TaskEvent event, AtomicBoolean done) {
        try {
            SseEmitter.SseEventBuilder b = SseEmitter.event()
                    .name(event.type().name())
                    .data(event.data(), MediaType.APPLICATION_JSON);
            if (event.eventId() != null) {
                b.id(event.eventId());   // 持久事件带 durable 游标(客户端据此续播);TOKEN 无 id
            }
            emitter.send(b);
            if (event.isTerminal() && done.compareAndSet(false, true)) {
                emitter.complete();
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);                 // 客户端断开 -> onError/onCompletion -> 退订
            return false;
        }
    }

    private static String defaultProfile(String profile) {
        return profile == null || profile.isBlank() ? "coding" : profile;
    }

    /** 请求体:{ "goal": "...", "profile": "coding" } */
    public record TaskRequest(String goal, String profile) {
    }
}
