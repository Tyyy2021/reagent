package com.reagent.acceptance;

import com.reagent.persist.EventEntity;
import com.reagent.persist.EventRepository;
import com.reagent.stream.TaskEvent;
import com.reagent.testsupport.InfrastructureIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcceptanceEpochRepositoryIT extends InfrastructureIT {

    @Autowired
    private EventRepository events;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearEvents() {
        jdbc.update("DELETE FROM event");
    }

    @Test
    void queryReturnsTaskScopedDistinctEpochsInFirstEventOrder() {
        save("task-epochs", TaskEvent.Type.TASK_STARTED, "{\"leaseEpoch\":7}", 0);
        save("task-epochs", TaskEvent.Type.STEP, "{\"leaseEpoch\":99}", 1);
        save("other-task", TaskEvent.Type.TASK_STARTED, "{\"leaseEpoch\":99}", 2);
        save("task-epochs", TaskEvent.Type.TASK_STARTED, "{\"leaseEpoch\":7}", 3);
        save("task-epochs", TaskEvent.Type.TASK_STARTED, "{\"leaseEpoch\":2}", 4);

        List<String> actual = events.findOrderedDistinctEpochValues(
                "task-epochs",
                TaskEvent.Type.TASK_STARTED.name(),
                PageRequest.of(0, 17));

        assertEquals(List.of("7", "2"), actual);
    }

    @Test
    void queryReturnsOnlyTheFirstSeventeenDistinctEpochs() {
        LongStream.rangeClosed(1, 18).forEach(epoch ->
                save(
                        "task-overflow",
                        TaskEvent.Type.TASK_STARTED,
                        "{\"leaseEpoch\":" + epoch + "}",
                        epoch));

        List<String> actual = events.findOrderedDistinctEpochValues(
                "task-overflow",
                TaskEvent.Type.TASK_STARTED.name(),
                PageRequest.of(0, 17));

        assertEquals(
                LongStream.rangeClosed(1, 17).mapToObj(Long::toString).toList(),
                actual);
    }

    @Test
    void querySurfacesEveryMalformedEpochAlongsideValidEvidence() {
        List<String> malformed = List.of(
                "{\"leaseEpoch\":\"1\"}",
                "{\"leaseEpoch\":null}",
                "{}",
                "{not-json");

        for (int index = 0; index < malformed.size(); index++) {
            String taskId = "task-malformed-" + index;
            save(taskId, TaskEvent.Type.TASK_STARTED, "{\"leaseEpoch\":2}", 0);
            save(taskId, TaskEvent.Type.TASK_STARTED, malformed.get(index), 1);

            List<String> actual = events.findOrderedDistinctEpochValues(
                    taskId,
                    TaskEvent.Type.TASK_STARTED.name(),
                    PageRequest.of(0, 17));

            assertEquals(
                    List.of("2", "__INVALID_LEASE_EPOCH__"),
                    actual,
                    malformed.get(index));
        }
    }

    private void save(
            String taskId,
            TaskEvent.Type type,
            String data,
            long seconds
    ) {
        events.saveAndFlush(new EventEntity(
                taskId,
                type.name(),
                data,
                Instant.EPOCH.plusSeconds(seconds)));
    }
}
