package com.reagent.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨 worker 事件流(M7 Stage5)—— {@link StreamTransport} 的 <b>Redis Streams 实现</b>(reagent.streaming.transport=redis)。
 *
 * <p>每个任务一个 Redis Stream {@code reagent:stream:<taskId>}。worker 无状态:任意 worker 都能服务任意任务的 SSE。</p>
 * <ul>
 *   <li><b>publish</b>:非 TOKEN 仍落 event 表(长期 durable / CQRS / fallback 源),所有事件(含 TOKEN){@code XADD}
 *       进 stream,stream 记录 id 作 SSE 续播游标;任务终态后给 stream 设 TTL 自动清,内存不涨。</li>
 *   <li><b>subscribe</b>:一根虚拟线程 {@code XREAD BLOCK from cursor} 连续读 —— <b>replay 历史与 live 是同一个
 *       游标读的连续</b>,天然不漏不重(消掉了 in-process 那套补播/live 的每任务锁 handoff)。stream 已 TTL 过期的
 *       老任务 → 回落 event 表全量补播(老任务无 live)。</li>
 * </ul>
 *
 * <p><b>诚实分层</b>:Stream 在 TTL 存活期内是 at-least-once(重连从游标续读、连 token 都补得回);超 TTL 的老任务
 * 靠 event 表 replay 兜底非 TOKEN 事件、token 认逝。与 M4「durable 非 TOKEN、live-only token」一脉相承。超长任务可
 * 再加 {@code XADD MAXLEN ~} trim(留后续;单任务事件量通常远小于 TTL 窗口)。</p>
 */
@Component
@ConditionalOnProperty(name = "reagent.streaming.transport", havingValue = "redis")
public class RedisStreamTransport implements StreamTransport {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamTransport.class);
    private static final String KEY_PREFIX = "reagent:stream:";
    /** XREAD 阻塞时长:到点没新事件就回头查 close 标志(故退订最多延迟这么久生效)。 */
    private static final Duration BLOCK = Duration.ofSeconds(2);

    private final StringRedisTemplate redis;
    private final EventStore eventStore;
    private final ObjectMapper mapper;
    private final long ttlSec;
    /** 每个 SSE 订阅一根虚拟线程跑 XREAD-BLOCK 自旋(阻塞读不占平台线程)。 */
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

    public RedisStreamTransport(StringRedisTemplate redis, EventStore eventStore, ObjectMapper mapper,
                                @Value("${reagent.streaming.redis-stream-ttl-sec:3600}") long ttlSec) {
        this.redis = redis;
        this.eventStore = eventStore;
        this.mapper = mapper;
        this.ttlSec = ttlSec;
        log.info("事件流传输 = redis(Redis Streams 跨 worker live 总线,stream TTL={}s)", ttlSec);
    }

    private static String key(String taskId) {
        return KEY_PREFIX + taskId;
    }

    @Override
    public TaskEvent publish(String taskId, TaskEvent.Type type, Object data) {
        // 非 TOKEN 仍落 event 表:长期 durable / CQRS 投影 + stream TTL 过期后的 fallback replay 源
        String durableId = (type == TaskEvent.Type.TOKEN) ? null : String.valueOf(eventStore.append(taskId, type, data));
        // 所有事件(含 TOKEN)进 per-task Stream;XADD 自动生成 stream id 作 SSE 游标(订阅端 XREAD 读到它)
        Map<String, String> fields = Map.of("type", type.name(), "data", toJson(data));
        RecordId rid = redis.opsForStream().add(key(taskId), fields);
        String eventId = (type == TaskEvent.Type.TOKEN) ? null : (rid != null ? rid.getValue() : durableId);
        TaskEvent event = new TaskEvent(taskId, eventId, type, data, Instant.now());
        if (event.isTerminal()) {
            redis.expire(key(taskId), Duration.ofSeconds(ttlSec));   // 任务收尾:给 stream 设 TTL,跑完自动清
        }
        return event;
    }

    @Override
    public Subscription subscribeWithReplay(String taskId, String cursor, EventSink sink) {
        String key = key(taskId);
        // stream 不存在(任务老早完成、key 已 TTL 过期)→ 回落 event 表全量补播(老任务无 live),从头给
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            for (TaskEvent past : eventStore.replayAfter(taskId, 0L)) {
                if (!safeDeliver(sink, past)) {
                    break;
                }
            }
            return () -> { };
        }
        // stream 存在:一根虚拟线程跑 XREAD-from-cursor 连续读(replay 历史 + BLOCK live 同一序列,不漏不重)
        String start = (cursor == null || cursor.isBlank()) ? "0" : cursor.trim();
        AtomicBoolean closed = new AtomicBoolean(false);
        readers.submit(() -> readLoop(key, start, sink, closed));
        return () -> closed.set(true);
    }

    private void readLoop(String key, String start, EventSink sink, AtomicBoolean closed) {
        String lastId = start;
        StreamReadOptions opts = StreamReadOptions.empty().block(BLOCK).count(100);
        try {
            while (!closed.get()) {
                List<MapRecord<String, Object, Object>> records =
                        redis.opsForStream().read(opts, StreamOffset.create(key, ReadOffset.from(lastId)));
                if (records == null || records.isEmpty()) {
                    continue;   // BLOCK 到点无新事件,回头查 closed
                }
                for (MapRecord<String, Object, Object> r : records) {
                    lastId = r.getId().getValue();
                    if (!safeDeliver(sink, toEvent(key, r))) {
                        closed.set(true);
                        break;
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Redis 读流出错,结束该订阅 key={}", key, ex);
        }
    }

    private TaskEvent toEvent(String key, MapRecord<String, Object, Object> r) {
        Map<Object, Object> v = r.getValue();
        TaskEvent.Type type = TaskEvent.Type.valueOf(String.valueOf(v.get("type")));
        Object data = fromJson(String.valueOf(v.get("data")));
        // TOKEN live-only:不带 SSE id(与 in-process 同构);其余用 stream 记录 id 作续播游标
        String eventId = (type == TaskEvent.Type.TOKEN) ? null : r.getId().getValue();
        String taskId = key.substring(KEY_PREFIX.length());
        return new TaskEvent(taskId, eventId, type, data, Instant.now());
    }

    private boolean safeDeliver(EventSink sink, TaskEvent event) {
        try {
            return sink.deliver(event);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String toJson(Object data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件负载序列化失败: " + data, e);
        }
    }

    private Object fromJson(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件负载反序列化失败: " + json, e);
        }
    }
}
