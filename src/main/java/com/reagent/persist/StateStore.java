package com.reagent.persist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.Context;
import com.reagent.core.ToolCall;
import com.reagent.core.WorkerIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ M2 的核心:状态存储层。把内存里的 agent 运行状态"投影"到数据库,并能反向重建。
 *
 * 三件事:
 *  1. 每步落库     —— appendXxx():agent 每往 Context 加一条消息,就同步写 message 表;
 *                     assistant 要调工具时,顺带在 tool_call 表登记 PENDING。
 *  2. 重建上下文   —— loadContext():崩溃重启后,从 message 表把发给模型的 messages 数组 1:1 还原。
 *  3. 幂等/状态机  —— statusOf()/markInProgress()/recordToolResult()/markInDoubt():维护 tool_call 的
 *                     PENDING→IN_PROGRESS→DONE|IN_DOUBT 状态机,恢复时据此复用/重跑/上报(exactly-once)。
 *
 * message 表是上下文的"唯一真相源";tool_call 表只服务于幂等判断。
 */
@Service
public class StateStore {

    private final TaskRepository taskRepo;
    private final MessageRepository messageRepo;
    private final ToolCallRepository toolCallRepo;
    private final ObjectMapper mapper;
    private final WorkerIdentity worker;        // M7:本 worker 身份(claim 的 owner)
    private final long leaseTtlMs;              // M7:租约时长

    public StateStore(TaskRepository taskRepo, MessageRepository messageRepo,
                      ToolCallRepository toolCallRepo, ObjectMapper mapper,
                      WorkerIdentity worker,
                      @Value("${reagent.worker.lease-ttl-ms:30000}") long leaseTtlMs) {
        this.taskRepo = taskRepo;
        this.messageRepo = messageRepo;
        this.toolCallRepo = toolCallRepo;
        this.mapper = mapper;
        this.worker = worker;
        this.leaseTtlMs = leaseTtlMs;
    }

    // ===================== 任务生命周期 =====================

    /** 新建任务,并把开场的 system + user(goal) 两条消息落库 */
    @Transactional
    public TaskEntity createTask(String goal, String systemPrompt) {
        // M7:新任务出生即认领租约(owner=本 worker),避免出现"无主 RUNNING"空窗 —— 否则创建者若在
        // createTask 与首次 drive 之间崩了,该任务会成 owner=null/lease=null 的孤儿,失效扫描(只盯过期租约)永远漏掉。
        TaskEntity t = TaskEntity.newTask(goal);
        t.assignLease(worker.id(), Instant.now().plusMillis(leaseTtlMs));
        TaskEntity task = taskRepo.save(t);
        appendMessage(task.getId(), "system", systemPrompt, null, null);
        appendMessage(task.getId(), "user", goal, null, null);
        return task;
    }

    public TaskEntity getTask(String taskId) {
        return taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("找不到任务: " + taskId));
    }

    /** 崩溃恢复扫描:所有仍处于 RUNNING 的任务 */
    public List<TaskEntity> findRunning() {
        return taskRepo.findByStatus(TaskStatus.RUNNING);
    }

    /**
     * ★ M7 Stage1:抢占任务执行租约(原子 claim 的薄封装)。委托 {@link TaskRepository#claim} 做
     * 单条条件 UPDATE,owner=本 worker、续租 {@code leaseTtlMs}。
     *
     * @return true = 本 worker 抢到执行权(可驱动);false = 别人持活租约,应跳过本次驱动。
     */
    @Transactional
    public boolean claim(String taskId) {
        Instant now = Instant.now();
        int rows = taskRepo.claim(taskId, worker.id(), now, now.plusMillis(leaseTtlMs));
        return rows == 1;
    }

    /**
     * ★ M7 Stage2:心跳续租 —— 把本 worker 持有的任务租约往后续 {@code leaseTtlMs}。
     * @return true = 续上了(仍归我);false = 这任务已不归我(被接管 / 已释放),调用方据此自停(Stage3)。
     */
    @Transactional
    public boolean renew(String taskId) {
        return taskRepo.renew(taskId, worker.id(), Instant.now().plusMillis(leaseTtlMs)) == 1;
    }

    /** ★ M7 Stage2:失效扫描 —— 取最多 {@code limit} 个 RUNNING 且租约已过期的孤儿任务(供失败转移接管)。 */
    public List<TaskEntity> findExpired(int limit) {
        return taskRepo.findByStatusAndLeaseExpiresAtLessThan(TaskStatus.RUNNING, Instant.now(), Limit.of(limit));
    }

    @Transactional
    public void completeTask(String taskId, String answer) {
        TaskEntity t = getTask(taskId);
        t.complete(answer);
        taskRepo.save(t);
    }

    @Transactional
    public void failTask(String taskId, String error) {
        TaskEntity t = getTask(taskId);
        t.fail(error);
        taskRepo.save(t);
    }

    /** M4:用户取消(终态)。 */
    @Transactional
    public void cancelTask(String taskId, String note) {
        TaskEntity t = getTask(taskId);
        t.cancel(note);
        taskRepo.save(t);
    }

    /** M4:用户暂停(非终态,仅显式 resume 续跑)。 */
    @Transactional
    public void pauseTask(String taskId) {
        TaskEntity t = getTask(taskId);
        t.pause();
        taskRepo.save(t);
    }

    /** M4:PAUSED -> RUNNING(resume 续跑前)。 */
    @Transactional
    public void markRunning(String taskId) {
        TaskEntity t = getTask(taskId);
        t.markRunning();
        taskRepo.save(t);
    }

    /** 崩溃恢复:把该任务的恢复计数 +1 并持久化,返回新的计数值(这是第几次恢复)。 */
    @Transactional
    public int incrementRecoveryCount(String taskId) {
        TaskEntity t = getTask(taskId);
        int n = t.incrementRecovery();
        taskRepo.save(t);
        return n;
    }

    // ===================== 落库:每步 =====================

    /**
     * 落库一条 assistant 消息。若它带 tool_calls,顺带在 tool_call 表登记 PENDING(供幂等用)。
     * content 可能为 null(模型只调工具、不带文本时)。
     */
    @Transactional
    public void appendAssistant(String taskId, Map<String, Object> assistantMessage) {
        String content = asStringOrNull(assistantMessage.get("content"));
        Object toolCalls = assistantMessage.get("tool_calls");
        String toolCallsJson = toolCalls == null ? null : toJson(toolCalls);

        appendMessage(taskId, "assistant", content, toolCallsJson, null);

        if (toolCalls instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> tc)) continue;
                String id = String.valueOf(tc.get("id"));
                Map<?, ?> fn = (Map<?, ?>) tc.get("function");
                String name = String.valueOf(fn.get("name"));
                String args = asArguments(fn.get("arguments"));
                // 同一 id 不重复登记(恢复路径里 assistant 不会被重复落库,这里仅作防御)
                if (!toolCallRepo.existsById(id)) {
                    toolCallRepo.save(new ToolCallEntity(id, taskId, name, args));
                }
            }
        }
    }

    /**
     * 执行<b>前</b>把这次调用标成 IN_PROGRESS 并 commit —— exactly-once 的关键栅栏:
     * 让恢复时能区分"PENDING=副作用一定没发生(安全重跑)"与"IN_PROGRESS=可能做了一半/跑完没记(in-doubt)"。
     * attemptCount 也在此 +1,供 per-tool 重试上限止损。
     */
    @Transactional
    public void markInProgress(String taskId, ToolCall call) {
        ToolCallEntity e = toolCallRepo.findById(call.id())
                .orElseGet(() -> new ToolCallEntity(call.id(), taskId, call.name(), call.arguments()));
        e.markInProgress();
        toolCallRepo.save(e);
    }

    /**
     * 工具执行完后:更新账本为 DONE + 结果,并落一条 tool 结果消息。
     * 二者在同一事务里,尽量缩小"账本与消息不一致"的窗口。
     */
    @Transactional
    public void recordToolResult(String taskId, ToolCall call, String result) {
        ToolCallEntity e = toolCallRepo.findById(call.id())
                .orElseGet(() -> new ToolCallEntity(call.id(), taskId, call.name(), call.arguments()));
        e.markDone(result);
        toolCallRepo.save(e);
        appendMessage(taskId, "tool", result, null, call.id());
    }

    /**
     * 非幂等工具崩在 in-doubt 窗口、又无 journal 完成记录:判定"结果存疑",落 IN_DOUBT + 一条 tool 消息
     * (把"未知"作为观察回给模型,既满足"每个 tool_call 必有结果"的协议,又绝不替它重放副作用)。
     */
    @Transactional
    public void markInDoubt(String taskId, ToolCall call, String result) {
        ToolCallEntity e = toolCallRepo.findById(call.id())
                .orElseGet(() -> new ToolCallEntity(call.id(), taskId, call.name(), call.arguments()));
        e.markInDoubt(result);
        toolCallRepo.save(e);
        appendMessage(taskId, "tool", result, null, call.id());
    }

    /** 账本当前状态(用于恢复决策表);账本里没有这条 = 从没登记过 → 当 PENDING(安全重跑)处理。 */
    public ToolCallStatus statusOf(String toolCallId) {
        return toolCallRepo.findById(toolCallId)
                .map(ToolCallEntity::getStatus)
                .orElse(ToolCallStatus.PENDING);
    }

    // ===================== 重建上下文 =====================

    /** 从 message 表把整个对话历史【按自增 id 顺序】还原成内存 Context(id 即插入顺序,稳定可靠) */
    public Context loadContext(String taskId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (MessageEntity row : messageRepo.findByTaskIdOrderByIdAsc(taskId)) {
            messages.add(toMessageMap(row));
        }
        return Context.fromMessages(messages);
    }

    /** 把一行 message 还原成发给模型的那种 map 形态 */
    private Map<String, Object> toMessageMap(MessageEntity row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", row.getRole());
        if ("tool".equals(row.getRole())) {
            m.put("tool_call_id", row.getToolCallId());
            m.put("content", row.getContent());
        } else if (row.getToolCallsJson() != null) {
            m.put("content", row.getContent());       // 可能为 null,符合协议
            m.put("tool_calls", fromJsonList(row.getToolCallsJson()));
        } else {
            m.put("content", row.getContent());
        }
        return m;
    }

    // ===================== 内部小工具 =====================

    private void appendMessage(String taskId, String role, String content,
                               String toolCallsJson, String toolCallId) {
        int seq = messageRepo.countByTaskId(taskId);
        messageRepo.save(new MessageEntity(taskId, seq, role, content, toolCallsJson, toolCallId));
    }

    private static String asStringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** arguments 在 OpenAI/DeepSeek 协议里是字符串;Ollama 可能给对象,统一转成字符串 */
    private String asArguments(Object arguments) {
        return arguments instanceof String s ? s : toJson(arguments);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化失败: " + o, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonList(String json) {
        try {
            return mapper.readValue(json, List.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("反序列化 tool_calls 失败: " + json, ex);
        }
    }
}
