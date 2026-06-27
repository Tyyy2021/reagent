package com.reagent.api;

import com.reagent.core.AgentRunner;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对外入口。
 *
 * M1 只有"提交并同步跑完"。M2 加了任务 id 与状态查询、以及手动恢复入口,
 * 因为现在任务的状态是持久化的、可被中断与续跑的。
 *
 * M4 会把提交改成异步 + SSE 流式输出;M7 接入任务队列与调度。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final AgentRunner runner;
    private final StateStore stateStore;

    public TaskController(AgentRunner runner, StateStore stateStore) {
        this.runner = runner;
        this.stateStore = stateStore;
    }

    /** 提交目标,同步跑完返回 { taskId, goal, result } */
    @PostMapping
    public Map<String, String> submit(@RequestBody TaskRequest request) {
        AgentRunner.RunResult r = runner.run(request.goal());
        return Map.of(
                "taskId", r.taskId(),
                "goal", request.goal(),
                "result", r.result()
        );
    }

    /** 查任务状态:{ taskId, status, goal, result } */
    @GetMapping("/{id}")
    public Map<String, String> status(@PathVariable String id) {
        TaskEntity t = stateStore.getTask(id);
        return Map.of(
                "taskId", t.getId(),
                "status", t.getStatus().name(),
                "goal", t.getGoal(),
                "result", t.getResult() == null ? "" : t.getResult()
        );
    }

    /** 手动恢复一个 RUNNING 任务(平时由启动时崩溃恢复自动触发,这里方便手测) */
    @PostMapping("/{id}/resume")
    public Map<String, String> resume(@PathVariable String id) {
        String result = runner.resume(id);
        return Map.of("taskId", id, "result", result);
    }

    /** 请求体:{ "goal": "..." } */
    public record TaskRequest(String goal) {
    }
}
