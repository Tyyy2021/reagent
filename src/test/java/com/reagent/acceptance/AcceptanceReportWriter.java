package com.reagent.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AcceptanceReportWriter {

    private static final Path DIRECTORY = Path.of("target", "acceptance");
    private static final Path JSON = DIRECTORY.resolve("incident-evidence.json");
    private static final Path MARKDOWN = DIRECTORY.resolve("incident-evidence.md");
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final List<Map<String, Object>> ENTRIES = new ArrayList<>();

    private AcceptanceReportWriter() {
    }

    public static synchronized void reset() {
        ENTRIES.clear();
        write();
    }

    public static synchronized void record(
            String testName,
            long durationMs,
            AcceptanceEvidence evidence
    ) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("testName", testName);
        entry.put("durationMs", durationMs);
        entry.put("contractVersion", evidence.contractVersion());
        entry.put("taskId", evidence.taskId());
        entry.put("profileId", evidence.profileId());
        entry.put("taskStatus", evidence.taskStatus());
        entry.put("citationIds", evidence.citationIds());
        entry.put("citationSources", evidence.citationSources());
        entry.put("mcpTools", evidence.mcpTools());
        entry.put("approvalDecision", evidence.approvalDecision());
        entry.put("workerEpochs", evidence.workerEpochs());
        entry.put("ticketId", evidence.ticketId());
        entry.put("createTicketAttempts", evidence.createTicketAttempts());
        entry.put("uniqueTicketCount", evidence.uniqueTicketCount());
        entry.put("passed", evidence.passed());
        ENTRIES.add(Map.copyOf(entry));
        write();
    }

    private static void write() {
        try {
            Files.createDirectories(DIRECTORY);
            MAPPER.writeValue(JSON.toFile(), Map.of(
                    "contractVersion", 1,
                    "scenarios", List.copyOf(ENTRIES)));
            StringBuilder markdown = new StringBuilder(
                    "# Incident acceptance\n\n"
                            + "| Scenario | Status | Approval | Attempts | Unique | Passed |\n"
                            + "|---|---|---|---:|---:|---|\n");
            for (Map<String, Object> entry : ENTRIES) {
                markdown.append("| ")
                        .append(entry.get("testName")).append(" | ")
                        .append(entry.get("taskStatus")).append(" | ")
                        .append(entry.get("approvalDecision")).append(" | ")
                        .append(entry.get("createTicketAttempts")).append(" | ")
                        .append(entry.get("uniqueTicketCount")).append(" | ")
                        .append(entry.get("passed")).append(" |\n");
            }
            Files.writeString(MARKDOWN, markdown);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write acceptance report", failure);
        }
    }
}
