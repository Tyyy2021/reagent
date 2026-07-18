package com.reagent.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.FencedExecutionException;
import com.reagent.core.TaskRunToken;
import com.reagent.persist.EventEntity;
import com.reagent.persist.EventRepository;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link EventStore} 的 JPA 实现 —— 事件流持久化到 {@code event} 表(自增 id = durable 游标)。
 *
 * <p>{@code append} 在自己的事务里提交(发布路径无外层事务,REQUIRED 即提交):保证事件在被任何订阅者
 * 观测到【之前】就已落库——客户端持有的 Last-Event-ID 永远指向一行已 commit 的事件。</p>
 *
 * <p>{@code replayAfter} 把历史行重建成与 live <b>完全同构</b>的 {@link TaskEvent}(eventId=行 id、
 * at=created_at、data 反序列化回对象),好让补播事件走与实时事件【完全相同】的下游发送路径。</p>
 */
@Service
public class JpaEventStore implements EventStore {

    private final EventRepository repo;
    private final TaskRepository taskRepository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JpaEventStore(EventRepository repo, TaskRepository taskRepository,
                         ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.taskRepository = taskRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public long append(String taskId, TaskEvent.Type type, Object data) {
        return appendRow(taskId, type, data, clock.instant());
    }

    @Override
    @Transactional
    public long appendFenced(TaskRunToken token, TaskEvent.Type type, Object data) {
        TaskEntity task = taskRepository.findByIdForUpdate(token.taskId())
                .orElseThrow(() -> new FencedExecutionException(token, null, -1, null));
        if (task.getLeaseEpoch() != token.leaseEpoch()
                || (task.getOwnerId() != null
                && !Objects.equals(task.getOwnerId(), token.workerId()))) {
            throw new FencedExecutionException(
                    token, task.getOwnerId(), task.getLeaseEpoch(), task.getStatus());
        }
        return appendRow(task.getId(), type, data, clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskEvent> replayAfter(String taskId, long afterEventId) {
        List<EventEntity> rows = repo.findByTaskIdAndIdGreaterThanOrderByIdAsc(taskId, afterEventId);
        List<TaskEvent> events = new ArrayList<>(rows.size());
        for (EventEntity row : rows) {
            events.add(new TaskEvent(taskId, String.valueOf(row.getId()),
                    TaskEvent.Type.valueOf(row.getType()), fromJson(row.getData()), row.getCreatedAt()));
        }
        return events;
    }

    private String toJson(Object data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件负载序列化失败: " + data, e);
        }
    }

    private long appendRow(String taskId, TaskEvent.Type type, Object data, Instant now) {
        EventEntity row = repo.save(new EventEntity(taskId, type.name(), toJson(data), now));
        return row.getId();   // IDENTITY 策略:save/flush 后自增 id 已就位
    }

    private Object fromJson(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件负载反序列化失败: " + json, e);
        }
    }
}
