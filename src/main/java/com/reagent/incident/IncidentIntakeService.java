package com.reagent.incident;

import com.reagent.core.AgentRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
public class IncidentIntakeService {

    private final IncidentCreationTransaction transactions;
    private final AgentRunner runner;
    private final Set<String> trustedSources;

    public IncidentIntakeService(
            IncidentCreationTransaction transactions,
            AgentRunner runner,
            IncidentProperties properties
    ) {
        this.transactions = transactions;
        this.runner = runner;
        this.trustedSources = Set.copyOf(properties.getTrustedSources());
    }

    public IncidentAccepted accept(IncidentRequest incident) {
        requireTrustedSource(incident.source());
        Optional<IncidentAccepted> existing = transactions.findExisting(
                incident.source(), incident.externalAlertId());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            IncidentAccepted accepted = transactions.create(incident);
            runner.resumeAsync(accepted.taskId());
            return accepted;
        } catch (DataIntegrityViolationException conflict) {
            return transactions.findExisting(incident.source(), incident.externalAlertId())
                    .orElseThrow(() -> conflict);
        }
    }

    private void requireTrustedSource(String source) {
        if (!trustedSources.contains(source)) {
            throw new IllegalArgumentException("Incident source is not trusted");
        }
    }
}
