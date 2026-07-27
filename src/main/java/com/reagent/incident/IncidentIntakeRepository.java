package com.reagent.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IncidentIntakeRepository extends JpaRepository<IncidentIntakeEntity, String> {

    Optional<IncidentIntakeEntity> findBySourceAndExternalAlertId(
            String source,
            String externalAlertId
    );

    Optional<IncidentIntakeEntity> findByTaskId(String taskId);
}
