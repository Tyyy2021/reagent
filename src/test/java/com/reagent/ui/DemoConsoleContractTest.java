package com.reagent.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoConsoleContractTest {

    private static final Pattern STORAGE_KEY =
            Pattern.compile("reagent\\.demo\\.v1\\.[A-Za-z]+");

    @Test
    void consoleIsOneAccessibleIncidentWorkspaceInsteadOfADetachedCardGrid()
            throws Exception {
        String html = resource("static/index.html");
        String css = resource("static/styles.css");

        assertTrue(html.contains("<header"));
        assertTrue(html.contains("<main id=\"incident-workspace\""));
        assertTrue(html.contains("id=\"readiness-strip\""));
        assertTrue(html.contains("id=\"incident-trigger\""));
        assertTrue(html.contains("id=\"task-context\""));
        assertTrue(html.contains("id=\"incident-timeline\""));
        assertTrue(html.contains("id=\"approval-panel\""));
        assertTrue(html.contains("id=\"citations\""));
        assertTrue(html.contains("id=\"metrics-evidence\""));
        assertTrue(html.contains("id=\"log-evidence\""));
        assertTrue(html.contains("id=\"final-outcome\""));
        assertTrue(html.contains("id=\"live-status\""));
        assertTrue(html.contains("role=\"status\""));
        assertTrue(html.contains("id=\"approve-action\""));
        assertTrue(html.contains("id=\"reject-action\""));
        assertTrue(html.contains("data-decision=\"APPROVE\""));
        assertTrue(html.contains("data-decision=\"REJECT\""));
        assertFalse(
                Pattern.compile("<span>\\s*<dt", Pattern.DOTALL)
                        .matcher(html)
                        .find(),
                "description terms must not be nested inside span elements");

        assertTrue(css.contains("@media (max-width: 900px)"));
        assertTrue(css.contains("@media (prefers-reduced-motion: reduce)"));
        assertFalse(css.contains("linear-gradient"));
        assertFalse(css.contains("radial-gradient"));
    }

    @Test
    void browserStateIsReplayableWithoutPersistingIncidentPayloads()
            throws Exception {
        String script = resource("static/app.js");

        assertEquals(
                Set.of("reagent.demo.v1.taskId", "reagent.demo.v1.cursor"),
                storageKeys(script));
        assertTrue(script.contains("localStorage"));
        assertFalse(script.contains("sessionStorage"));
        assertTrue(script.contains("new URLSearchParams"));
        assertTrue(script.contains("new EventSource"));
        assertTrue(script.contains("APPROVE"));
        assertTrue(script.contains("REJECT"));
        assertTrue(script.contains("APPROVAL_REQUIRED"));
        assertTrue(script.contains("RECOVERY_ATTEMPT"));
        assertTrue(script.contains("FENCED"));
        assertTrue(script.contains("DEDUPLICATED"));
    }

    @Test
    void approvalQueueSelectsPendingWorkAndRefreshesTheAuthoritativeList()
            throws Exception {
        String script = resource("static/app.js");

        assertTrue(script.contains("function selectApproval(approvals)"));
        assertTrue(script.contains("pending[0] || tickets.at(-1) || null"));
        assertTrue(script.contains("runtime.pendingApprovalCount = pending.length"));
        assertTrue(script.contains("await refreshAuthoritative(\"decision\")"));
    }

    @Test
    void approvalConflictsResumeTheStreamFromAuthoritativeTaskState()
            throws Exception {
        String script = resource("static/app.js");

        assertTrue(script.contains("function taskNeedsStream(task)"));
        assertTrue(script.contains(
                "const snapshot = await refreshAuthoritative(\"conflict\")"));
        assertTrue(script.contains(
                "if (taskNeedsStream(snapshot && snapshot.task))"));
        assertFalse(
                script.contains("if (!snapshot) openStream()"),
                "failed authoritative refresh must not reopen SSE");
    }

    @Test
    void terminalRestoreUsesAuthoritativeStateWithoutLeavingAnSseSubscription()
            throws Exception {
        String script = resource("static/app.js");
        Pattern terminalRestore = Pattern.compile(
                "if \\(task && TERMINAL_STATES\\.has\\(String\\(task\\.status\\)\\)\\)"
                        + "\\s*\\{\\s*await refreshAcceptance\\(\\);"
                        + "\\s*closeStream\\(\\);\\s*return;\\s*}\\s*openStream\\(\\)",
                Pattern.DOTALL);

        assertTrue(terminalRestore.matcher(script).find());
    }

    @Test
    void boundedApprovalEvidencePopulatesMetricAndLogSummaries()
            throws Exception {
        String script = resource("static/app.js");

        assertTrue(script.contains("function renderObservationEvidence(preview)"));
        assertTrue(script.contains("String(preview || \"\").slice(0, 512)"));
        assertTrue(script.contains("errorRatePercent"));
        assertTrue(script.contains("SQLTransientConnectionException"));
        assertTrue(script.contains(
                "renderObservationEvidence(approval.evidencePreview)"));
    }

    @Test
    void everyExternalValueUsesTheSingleBoundedTextPath() throws Exception {
        String html = resource("static/index.html");
        String script = resource("static/app.js");
        String allStatic = html + "\n" + script;

        assertTrue(script.contains("function textElement(tag, className, value)"));
        assertTrue(script.contains("node.textContent ="));
        assertTrue(script.contains("String(value).slice(0, 4000)"));
        assertFalse(allStatic.contains("innerHTML"));
        assertFalse(allStatic.contains("insertAdjacentHTML"));
        assertFalse(allStatic.contains("document.write"));
        assertFalse(allStatic.contains("eval("));
    }

    private static Set<String> storageKeys(String script) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        Matcher matcher = STORAGE_KEY.matcher(script);
        while (matcher.find()) {
            keys.add(matcher.group());
        }
        return Set.copyOf(keys);
    }

    private static String resource(String path) throws IOException {
        InputStream stream =
                DemoConsoleContractTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing classpath resource: " + path);
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
