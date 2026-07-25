package com.reagent.persist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.Context;
import com.reagent.core.TaskRunToken;
import com.reagent.core.ToolCall;
import com.reagent.core.WorkerIdentity;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.profile.TaskProfileSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final AgentProfileRegistry profileRegistry;
    private final WorkerIdentity worker;        // M7:本 worker 身份(claim 的 owner)
    private final long leaseTtlMs;              // M7:租约时长
    private final Clock clock;
    private final TaskLeaseGuard leaseGuard;

    @Value("${reagent.profiles.max-snapshot-bytes:262144}")
    private int maxProfileSnapshotBytes = 262_144;

    public StateStore(TaskRepository taskRepo, MessageRepository messageRepo,
                      ToolCallRepository toolCallRepo, ObjectMapper mapper,
                      AgentProfileRegistry profileRegistry,
                      WorkerIdentity worker,
                      @Value("${reagent.worker.lease-ttl-ms:30000}") long leaseTtlMs,
                      Clock clock,
                      TaskLeaseGuard leaseGuard) {
        this.taskRepo = taskRepo;
        this.messageRepo = messageRepo;
        this.toolCallRepo = toolCallRepo;
        this.mapper = mapper;
        this.profileRegistry = profileRegistry;
        this.worker = worker;
        this.leaseTtlMs = leaseTtlMs;
        this.clock = clock;
        this.leaseGuard = leaseGuard;
    }

    // ===================== 任务生命周期 =====================

    /** 新建任务,并把开场的 system + user(goal) 两条消息落库 */
    @Transactional
    public TaskEntity createTask(String goal, String systemPrompt) {
        // M7:新任务出生即认领租约(owner=本 worker),避免出现"无主 RUNNING"空窗 —— 否则创建者若在
        // createTask 与首次 drive 之间崩了,该任务会成 owner=null/lease=null 的孤儿,失效扫描(只盯过期租约)永远漏掉。
        Instant now = clock.instant();
        TaskEntity t = TaskEntity.newTask(goal, now);
        t.assignLease(worker.id(), now.plusMillis(leaseTtlMs), now);
        TaskEntity task = taskRepo.save(t);
        appendMessage(task.getId(), "system", systemPrompt, null, null, now);
        appendMessage(task.getId(), "user", goal, null, null, now);
        return task;
    }

    @Transactional
    public TaskEntity createTask(String goal, TaskProfileSnapshot snapshot) {
        String snapshotJson = serializeProfile(snapshot);
        Instant now = clock.instant();
        TaskEntity task = TaskEntity.newTask(goal, now);
        task.freezeProfile(snapshot.profileId(), snapshotJson);
        task.assignLease(worker.id(), now.plusMillis(leaseTtlMs), now);
        TaskEntity saved = taskRepo.save(task);
        appendMessage(saved.getId(), "system", snapshot.systemPrompt(), null, null, now);
        appendMessage(saved.getId(), "user", goal, null, null, now);
        return saved;
    }

    @Transactional(readOnly = true)
    public TaskProfileSnapshot loadProfile(String taskId) {
        TaskEntity task = taskRepo.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.getProfileSnapshot() == null) {
            throw new IllegalStateException(
                    "Legacy task profile materialization requires a claimed task run token: " + taskId);
        }
        return deserializeAndValidateProfile(task);
    }

    @Transactional
    public TaskProfileSnapshot loadProfile(TaskRunToken token) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        if (task.getProfileSnapshot() == null) {
            if (task.getProfileId() != null && !"coding".equals(task.getProfileId())) {
                throw new IllegalStateException("Legacy task has unsupported profile: " + task.getProfileId());
            }
            TaskProfileSnapshot coding = profileRegistry.snapshot("coding");
            task.freezeProfile(coding.profileId(), serializeProfile(coding));
            taskRepo.save(task);
        }
        return deserializeAndValidateProfile(task);
    }

    private TaskProfileSnapshot deserializeAndValidateProfile(TaskEntity task) {
        TaskProfileSnapshot snapshot = deserializeProfile(task.getProfileSnapshot());
        if (!snapshot.profileId().equals(task.getProfileId())) {
            throw new IllegalStateException("Task profile ID does not match persisted snapshot: " + task.getId());
        }
        return snapshot;
    }

    public TaskEntity getTask(String taskId) {
        return taskRepo.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /** 崩溃恢复扫描:所有仍处于 RUNNING 的任务 */
    public List<TaskEntity> findRunning() {
        return taskRepo.findByStatus(TaskStatus.RUNNING);
    }

    /**
     * ★ M7 Stage1:抢占任务执行租约(原子 claim 的薄封装)。委托 {@link TaskRepository#claim} 做
     * 单条条件 UPDATE,owner=本 worker、续租 {@code leaseTtlMs}。
     *
     * @return 本 worker 抢到执行权时返回完整 run token；别人持活租约时返回 empty。
     */
    @Transactional
    public Optional<TaskRunToken> claim(String taskId) {
        Instant now = clock.instant();
        int rows = taskRepo.claim(taskId, worker.id(), now, now.plusMillis(leaseTtlMs));
        if (rows != 1) {
            return Optional.empty();
        }
        return taskRepo.findById(taskId)
                .map(task -> new TaskRunToken(taskId, worker.id(), task.getLeaseEpoch()));
    }

    /**
     * ★ M7 Stage2:心跳续租 —— 把本 worker 持有的任务租约往后续 {@code leaseTtlMs}。
     * @return true = 续上了(仍归我);false = 这任务已不归我(被接管 / 已释放),调用方据此自停(Stage3)。
     */
    @Transactional
    public boolean renew(TaskRunToken token) {
        return taskRepo.renew(token.taskId(), token.workerId(), token.leaseEpoch(),
                clock.instant().plusMillis(leaseTtlMs)) == 1;
    }

    /** ★ M7 Stage2:失效扫描 —— 取最多 {@code limit} 个 RUNNING 且租约已过期的孤儿任务(供失败转移接管)。 */
    public List<TaskEntity> findRecoverable(int limit) {
        return taskRepo.findRecoverable(clock.instant(), Limit.of(limit));
    }

    // ===================== M7 Stage4:跨 worker 控制面 =====================

    /** ★ M7 Stage4:跨 worker 下达控制信号(写 DB,由 owner 在安全点消费)。true=已记录(任务在 RUNNING)。 */
    @Transactional
    public boolean requestControl(String taskId, String signal) {
        return taskRepo.requestControl(taskId, signal) == 1;
    }

    /** ★ M7 Stage4:读当前控制信号(NULL→"NONE")。owner 每个安全点轻量读。 */
    public String readControlSignal(String taskId) {
        String s = taskRepo.findControlSignal(taskId);
        return s == null ? "NONE" : s;
    }

    /** ★ M7 Stage4:owner 消费信号后清回 NONE。 */
    @Transactional
    public void clearControlSignal(TaskRunToken token) {
        leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        taskRepo.clearControlSignal(token.taskId());
    }

    /**
     * 完成任务(终态),带 M7 Stage3 token 守卫:仅当本 worker 仍持有该 epoch 的租约才写得进(顺带释放租约)。
     * 租约已被接管时抛 {@code FencedExecutionException}，调用方必须停手。
     */
    @Transactional
    public void completeTask(TaskRunToken token, String answer) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        task.complete(answer, clock.instant());
        taskRepo.save(task);
    }

    /** 失败置终态,带 epoch 守卫(语义同 {@link #completeTask})。 */
    @Transactional
    public void failTask(TaskRunToken token, String error) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        task.fail(error, clock.instant());
        taskRepo.save(task);
    }

    /** M4:用户取消(终态)。 */
    @Transactional
    public void cancelTask(TaskRunToken token, String note) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        task.cancel(note, clock.instant());
        task.clearControlSignal();
        taskRepo.save(task);
    }

    /** M4:用户暂停(非终态,仅显式 resume 续跑)。 */
    @Transactional
    public void pauseTask(TaskRunToken token) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        task.pause(clock.instant());
        task.clearControlSignal();
        taskRepo.save(task);
    }

    /** M4:PAUSED -> RUNNING(resume 续跑前)。 */
    @Transactional
    public void markRunning(String taskId) {
        TaskEntity t = getTask(taskId);
        t.markRunning(clock.instant());
        taskRepo.save(t);
    }

    /** 崩溃恢复:把该任务的恢复计数 +1 并持久化,返回新的计数值(这是第几次恢复)。 */
    @Transactional
    public int incrementRecoveryCount(TaskRunToken token) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        int n = task.incrementRecovery(clock.instant());
        taskRepo.save(task);
        return n;
    }

    /** 运行期恢复止损判定读取;先锁定并校验本次 run token，旧 worker 不得据此继续做控制决策。 */
    @Transactional
    public int recoveryCount(TaskRunToken token) {
        return leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING)).getRecoveryCount();
    }

    // ===================== 落库:每步 =====================

    /**
     * 落库一条 assistant 消息。若它带 tool_calls,顺带在 tool_call 表登记 PENDING(供幂等用)。
     * content 可能为 null(模型只调工具、不带文本时)。
     */
    @Transactional
    public int appendAssistant(TaskRunToken token, Map<String, Object> assistantMessage) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        Instant now = clock.instant();
        String content = asStringOrNull(assistantMessage.get("content"));
        Object rawToolCalls = assistantMessage.get("tool_calls");
        List<ToolCall> parsedCalls = rawToolCalls == null
                ? List.of()
                : ToolCall.parseAssistantToolCalls(rawToolCalls);
        String toolCallsJson = rawToolCalls == null ? null : toJson(rawToolCalls);

        Map<String, ToolCall> callsToCreate = new LinkedHashMap<>();
        for (ToolCall call : parsedCalls) {
            // 先校验整批 call 的任务归属,再落 assistant 消息:外任务同 ID 必须在任何持久化之前 fail closed。
            if (ownedToolCall(task.getId(), call.id()).isEmpty()) {
                callsToCreate.put(call.id(), call);
            }
        }
        int sequence = appendMessage(task.getId(), "assistant", content, toolCallsJson, null, now);
        List<ToolCallEntity> ledgerRows = callsToCreate.values().stream()
                .map(call -> new ToolCallEntity(
                        call.id(), task.getId(), call.name(), call.arguments(),
                        now, sequence))
                .toList();
        toolCallRepo.saveAll(ledgerRows);
        return sequence;
    }

    /**
     * 执行<b>前</b>把这次调用标成 IN_PROGRESS 并 commit —— exactly-once 的关键栅栏:
     * 让恢复时能区分"PENDING=副作用一定没发生(安全重跑)"与"IN_PROGRESS=可能做了一半/跑完没记(in-doubt)"。
     * attemptCount 也在此 +1,供 per-tool 重试上限止损。
     */
    @Transactional
    public void markInProgress(TaskRunToken token, ToolCall call) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        Instant now = clock.instant();
        ToolCallEntity e = ownedToolCall(task.getId(), call.id())
                .orElseGet(() -> new ToolCallEntity(
                        call.id(), task.getId(), call.name(), call.arguments(), now));
        e.markInProgress(now);
        toolCallRepo.save(e);
    }

    /**
     * 工具执行完后:更新账本为 DONE + 结果,并落一条 tool 结果消息。
     * 二者在同一事务里,尽量缩小"账本与消息不一致"的窗口。
     */
    @Transactional
    public void recordToolResult(TaskRunToken token, ToolCall call, String result) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        Instant now = clock.instant();
        ToolCallEntity e = ownedToolCall(task.getId(), call.id())
                .orElseGet(() -> new ToolCallEntity(
                        call.id(), task.getId(), call.name(), call.arguments(), now));
        e.markDone(result, now);
        toolCallRepo.save(e);
        appendMessage(task.getId(), "tool", result, null, call.id(), now);
    }

    /**
     * 非幂等工具崩在 in-doubt 窗口、又无 journal 完成记录:判定"结果存疑",落 IN_DOUBT + 一条 tool 消息
     * (把"未知"作为观察回给模型,既满足"每个 tool_call 必有结果"的协议,又绝不替它重放副作用)。
     */
    @Transactional
    public void markInDoubt(TaskRunToken token, ToolCall call, String result) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        Instant now = clock.instant();
        ToolCallEntity e = ownedToolCall(task.getId(), call.id())
                .orElseGet(() -> new ToolCallEntity(
                        call.id(), task.getId(), call.name(), call.arguments(), now));
        e.markInDoubt(result, now);
        toolCallRepo.save(e);
        appendMessage(task.getId(), "tool", result, null, call.id(), now);
    }

    /** 任务内账本当前状态;本任务没有这条(含其它任务占用同 ID)=从没登记过 → 当 PENDING 处理。 */
    public ToolCallStatus statusOf(String taskId, String toolCallId) {
        return toolCallRepo.findByIdAndTaskId(toolCallId, taskId)
                .map(ToolCallEntity::getStatus)
                .orElse(ToolCallStatus.PENDING);
    }

    /** 运行期工具分类读取;先锁定并校验本次 run token，再按任务边界查询账本。 */
    @Transactional
    public ToolCallStatus statusOf(TaskRunToken token, String toolCallId) {
        TaskEntity task = leaseGuard.lockOwned(token, java.util.EnumSet.of(TaskStatus.RUNNING));
        return statusOf(task.getId(), toolCallId);
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

    /** 返回本任务账本行；同 ID 已属于其它任务时在调用方修改任何实体前 fail closed。 */
    private Optional<ToolCallEntity> ownedToolCall(String taskId, String toolCallId) {
        Optional<ToolCallEntity> existing = toolCallRepo.findById(toolCallId);
        if (existing.isPresent() && !taskId.equals(existing.orElseThrow().getTaskId())) {
            throw new IllegalStateException(
                    "tool_call_id 已属于其它任务: " + toolCallId + ", 当前任务: " + taskId);
        }
        return existing;
    }

    private int appendMessage(String taskId, String role, String content,
                              String toolCallsJson, String toolCallId, Instant now) {
        int seq = messageRepo.nextSequenceForLockedTask(taskId);
        messageRepo.save(new MessageEntity(
                taskId, seq, role, content, toolCallsJson, toolCallId, now));
        return seq;
    }

    private static String asStringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化失败: " + o, ex);
        }
    }

    private String serializeProfile(TaskProfileSnapshot snapshot) {
        String json = toJson(snapshot);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxProfileSnapshotBytes) {
            throw new IllegalArgumentException(
                    "Task profile snapshot exceeds " + maxProfileSnapshotBytes + " bytes: " + bytes);
        }
        return json;
    }

    private TaskProfileSnapshot deserializeProfile(String json) {
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxProfileSnapshotBytes) {
            throw new IllegalStateException(
                    "Persisted task profile snapshot exceeds " + maxProfileSnapshotBytes + " bytes: " + bytes);
        }
        try {
            return mapper.readValue(json, TaskProfileSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot deserialize task profile snapshot", ex);
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
