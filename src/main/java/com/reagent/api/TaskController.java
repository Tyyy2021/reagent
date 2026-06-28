package com.reagent.api;

import com.reagent.core.AgentRunner;
import com.reagent.core.TaskControl;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskStatus;
import com.reagent.stream.TaskEventBus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final TaskEventBus bus;
    private final TaskControl taskControl;

    public TaskController(AgentRunner runner, StateStore stateStore, TaskEventBus bus, TaskControl taskControl) {
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
        if (sync) {
            AgentRunner.RunResult r = runner.run(request.goal());
            return ResponseEntity.ok(Map.of(
                    "taskId", r.taskId(), "goal", request.goal(), "result", r.result()));
        }
        String taskId = runner.submit(request.goal());
        return ResponseEntity.accepted().body(Map.of("taskId", taskId, "status", "RUNNING"));
    }

    /**
     * 订阅某任务的实时事件流(SSE)。已终态的任务直接补播最终态并收尾(实时流不会再来)。
     * SseEmitter 超时(1h)只是断开"视图",任务在虚拟线程上照跑——这正是执行/连接解耦。
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        TaskEntity task = stateStore.getTask(id);          // 找不到 -> IllegalArgumentException
        SseEmitter emitter = new SseEmitter(3_600_000L);

        // 已终态:从 DB 补播一条最终事件后收尾,不订阅
        if (task.getStatus() != TaskStatus.RUNNING) {
            sendSnapshotAndComplete(emitter, task);
            return emitter;
        }

        // RUNNING:订阅实时事件;done 防"终态事件 + 竞态补播"重复收尾
        AtomicBoolean done = new AtomicBoolean(false);
        TaskEventBus.Subscription sub = bus.subscribe(id, event -> {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.seq()))
                        .name(event.type().name())
                        .data(event.data(), MediaType.APPLICATION_JSON));
                if (event.isTerminal() && done.compareAndSet(false, true)) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);               // 客户端断开 -> onError -> 退订
            }
        });
        emitter.onCompletion(sub::close);
        emitter.onTimeout(() -> { sub.close(); emitter.complete(); });
        emitter.onError(e -> sub.close());

        // 发 hello;再 double-check:若刚好在"查状态→订阅"窗口里完成了,补播终态收尾(防漏最后事件)
        try {
            emitter.send(SseEmitter.event().name("HELLO")
                    .data(Map.of("taskId", id, "status", "RUNNING"), MediaType.APPLICATION_JSON));
            TaskEntity again = stateStore.getTask(id);
            if (again.getStatus() != TaskStatus.RUNNING && done.compareAndSet(false, true)) {
                sendSnapshotAndComplete(emitter, again);
            }
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
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
                "result", t.getResult() == null ? "" : t.getResult());
    }

    /** M4:取消任务。默认优雅(当前工具跑完后于安全点停);?force=true 硬杀(Stage3b)。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable String id,
                                                      @RequestParam(defaultValue = "false") boolean force) {
        boolean ok = taskControl.requestCancel(id, force);
        if (!ok) {
            return ResponseEntity.status(409).body(Map.of(
                    "taskId", id, "message", "任务不在运行中(可能已结束),无法取消"));
        }
        return ResponseEntity.accepted().body(Map.of("taskId", id,
                "message", force ? "已请求取消(force 硬杀)" : "已请求取消(优雅:当前工具跑完后停)"));
    }

    /** M4:暂停任务(优雅,留下可 resume 的干净状态)。 */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, String>> pause(@PathVariable String id) {
        boolean ok = taskControl.requestPause(id);
        if (!ok) {
            return ResponseEntity.status(409).body(Map.of(
                    "taskId", id, "message", "任务不在运行中,无法暂停"));
        }
        return ResponseEntity.accepted().body(Map.of("taskId", id,
                "message", "已请求暂停(当前工具跑完后停)"));
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

    /** 给 SSE 推一条"当前最终态"快照并收尾(用于已终态任务 / 竞态补播)。 */
    private void sendSnapshotAndComplete(SseEmitter emitter, TaskEntity t) {
        try {
            emitter.send(SseEmitter.event()
                    .name(t.getStatus().name())
                    .data(Map.of("status", t.getStatus().name(),
                                 "result", t.getResult() == null ? "" : t.getResult()),
                          MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    /** 请求体:{ "goal": "..." } */
    public record TaskRequest(String goal) {
    }
}
