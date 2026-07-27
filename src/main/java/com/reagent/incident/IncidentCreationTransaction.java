package com.reagent.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.obs.Trace;
import com.reagent.profile.AgentProfileRegistry;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class IncidentCreationTransaction {

    private final AgentProfileRegistry profiles;
    private final StateStore stateStore;
    private final IncidentGoalFactory goalFactory;
    private final IncidentIntakeRepository repository;
    private final Clock clock;
    private final ObjectMapper mapper;

    public IncidentCreationTransaction(
            AgentProfileRegistry profiles,
            StateStore stateStore,
            IncidentGoalFactory goalFactory,
            IncidentIntakeRepository repository,
            Clock clock,
            ObjectMapper mapper
    ) {
        this.profiles = profiles;
        this.stateStore = stateStore;
        this.goalFactory = goalFactory;
        this.repository = repository;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional
    public IncidentAccepted create(IncidentRequest incident) {
        TaskEntity task = stateStore.createTask(
                goalFactory.create(incident),
                taskId -> {
                    try (Scope ignored =
                                 Trace.logicalRootContext(taskId).makeCurrent()) {
                        return profiles.snapshot("incident-ops");
                    }
                });
        IncidentIntakeEntity row = IncidentIntakeEntity.create(
                UUID.randomUUID().toString(), incident, task.getId(), clock.instant(), mapper);
        repository.saveAndFlush(row);
        return new IncidentAccepted(row.getId(), task.getId(), false);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<IncidentAccepted> findExisting(String source, String externalAlertId) {
        return repository.findBySourceAndExternalAlertId(source, externalAlertId)
                .map(row -> new IncidentAccepted(row.getId(), row.getTaskId(), true));
    }
}
