package com.reagent.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellScriptContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final List<String> SCRIPTS = List.of(
            "scripts/lib/demo-common.sh",
            "scripts/demo-up.sh",
            "scripts/demo-alert.sh",
            "scripts/demo-smoke.sh",
            "scripts/demo-reject.sh",
            "scripts/demo-failover.sh",
            "scripts/demo-down.sh",
            "scripts/demo-reset.sh",
            "scripts/verify-all.sh");

    @Test
    void allScriptsAreStrictAndSyntacticallyValid() throws Exception {
        for (String relativePath : SCRIPTS) {
            String script = readRequired(relativePath);
            assertTrue(
                    script.contains("set -Eeuo pipefail"),
                    () -> relativePath + " must enable strict shell mode");
            assertBashSyntax(relativePath);
        }
    }

    @Test
    void commonHelpersFixProjectAndComposeFileAndBoundEveryWait()
            throws IOException {
        String common = readRequired("scripts/lib/demo-common.sh");
        assertTrue(common.contains("COMPOSE_PROJECT_NAME=\"reagent-demo\""));
        assertTrue(common.contains("docker compose"));
        assertTrue(common.contains("--project-name \"$COMPOSE_PROJECT_NAME\""));
        assertTrue(common.contains("--file \"$COMPOSE_FILE\""));
        assertTrue(common.contains("wait_http()"));
        assertTrue(common.contains("wait_json_field()"));
        assertTrue(common.contains("deadline=$((SECONDS + timeout_seconds))"));
        assertTrue(common.contains("SECONDS < deadline"));
        assertTrue(common.contains("compose ps"));
        assertTrue(common.contains("compose logs --tail"));

        for (String relativePath : SCRIPTS.subList(1, SCRIPTS.size())) {
            String script = readRequired(relativePath);
            assertTrue(
                    script.contains("scripts/lib/demo-common.sh"),
                    () -> relativePath + " must source the common helper");
            assertFalse(
                    script.contains("docker compose"),
                    () -> relativePath + " must use the fixed compose wrapper");
        }
    }

    @Test
    void scriptsAvoidBroadDeletionUnboundedParsingAndImplicitVolumeLoss()
            throws IOException {
        for (String relativePath : SCRIPTS) {
            String script = readRequired(relativePath);
            assertFalse(script.contains("docker system prune"), relativePath);
            assertFalse(script.contains("rm -rf"), relativePath);
            assertFalse(script.contains("jq "), relativePath);
            assertFalse(script.contains("while true"), relativePath);
            if (!relativePath.endsWith("demo-reset.sh")) {
                assertFalse(script.contains("down -v"), relativePath);
                assertFalse(script.contains("down --volumes"), relativePath);
            }
        }

        String down = readRequired("scripts/demo-down.sh");
        assertTrue(down.contains(
                "compose --profile failover down --remove-orphans"));
        assertFalse(down.contains("-v"));
        assertFalse(down.contains("--volumes"));

        String reset = readRequired("scripts/demo-reset.sh");
        assertTrue(reset.contains(
                "compose --profile failover down -v --remove-orphans"));
    }

    @Test
    void alertIdsAndJsonParsingAreRunScopedAndDoNotDependOnJq()
            throws IOException {
        String common = readRequired("scripts/lib/demo-common.sh");
        assertTrue(common.contains("new_external_alert_id()"));
        assertTrue(common.contains("/dev/urandom"));
        assertTrue(common.contains(
                "ALERT-CHECKOUT-${scenario^^}-${stamp}-${nonce}"));
        assertTrue(common.contains("json_field()"));
        assertTrue(common.contains("python3"));
        assertTrue(common.contains("json.load(sys.stdin)"));

        String alert = readRequired("scripts/demo-alert.sh");
        String alertFlow = common + "\n" + alert;
        assertTrue(alertFlow.contains(
                "python -m agent_capabilities.fake_ops.alerts"));
        assertTrue(alertFlow.contains("/api/incidents"));
        assertTrue(alertFlow.contains("deduplicated"));

        for (String scenario : List.of(
                "demo-smoke.sh", "demo-reject.sh", "demo-failover.sh")) {
            String script = readRequired("scripts/" + scenario);
            assertTrue(script.contains("new_external_alert_id"));
            assertFalse(
                    script.contains("${1:-"),
                    () -> scenario + " must never accept a reusable fixture ID");
        }
    }

    @Test
    void failoverKillsOnlyTheResolvedWorkerAContainer() throws IOException {
        String failover = readRequired("scripts/demo-failover.sh");
        int resolve = failover.indexOf("compose ps -q reagent-worker-a");
        int bounded = failover.lastIndexOf("run_bounded", failover.indexOf("docker kill"));
        int kill = failover.indexOf("docker kill");

        assertTrue(resolve >= 0, "failover must resolve Worker A by Compose service");
        assertTrue(
                bounded > resolve && bounded < kill,
                "the exact-ID Docker kill must use the bounded command wrapper");
        assertTrue(kill > resolve, "Worker A resolution must happen before kill");
        assertTrue(failover.contains("\"$worker_a_container_id\""));
        assertFalse(failover.contains("compose kill reagent-worker-a"));
        assertFalse(failover.contains("docker kill reagent-worker-a"));
    }

    @Test
    void ciCollectsOnlyABoundedComposeLogTail() throws IOException {
        String workflow = readRequired(".github/workflows/ci.yml");
        boolean hasBoundedLogs = workflow.lines()
                .map(String::trim)
                .anyMatch(line -> line.matches(
                        "logs --no-color --tail [1-9][0-9]* \\\\"));

        assertTrue(
                hasBoundedLogs,
                "CI Compose evidence must cap the number of uploaded log lines");
    }

    @Test
    void fullVerificationSeedsItsPreparedModelCacheAfterReset()
            throws IOException {
        String verify = readRequired("scripts/verify-all.sh");
        int reset = verify.indexOf("bash \"$SCRIPT_DIR/demo-reset.sh\"");
        int seed = verify.indexOf("seed_model_cache \"$model_cache\"");
        int smoke = verify.indexOf("bash \"$SCRIPT_DIR/demo-smoke.sh\"");

        assertTrue(reset >= 0, "full verification must explicitly reset Compose");
        assertTrue(
                seed > reset,
                "the prepared model cache must seed the fresh named volume");
        assertTrue(
                smoke > seed,
                "offline cache seeding must complete before the smoke scenario");

        String common = readRequired("scripts/lib/demo-common.sh");
        assertTrue(common.contains("seed_model_cache()"));
        assertTrue(common.contains("compose run --rm --no-deps"));
    }

    @Test
    void fullVerificationPreparesOnlyTheProductionFilteredModelArtifacts()
            throws IOException {
        String verify = readRequired("scripts/verify-all.sh");

        assertTrue(
                verify.contains(
                        "from agent_capabilities.rag.embedding import MiniLmEmbedding"),
                "model-cache preparation must use the production filtered adapter");
        assertTrue(
                verify.contains("MiniLmEmbedding(cache_dir=Path("),
                "model-cache preparation must pass the fixed verification cache");
        assertFalse(
                verify.contains(
                        "SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"),
                "an unfiltered model-ID load pollutes the exact ten-file snapshot");
    }

    @Test
    void localAndCiQualityUseTheFixedFilteredModelCache() throws IOException {
        String verify = readRequired("scripts/verify-all.sh");
        int localQuality = verify.indexOf("-m quality");
        int localHome = verify.lastIndexOf(
                "HF_HOME=\"$model_cache\"", localQuality);
        assertTrue(localQuality >= 0, "full verification must run the quality gate");
        assertTrue(
                localHome >= 0,
                "local quality must receive its required fixed HF_HOME");

        String workflow = readRequired(".github/workflows/ci.yml");
        assertTrue(
                workflow.contains(
                        "from agent_capabilities.rag.embedding import MiniLmEmbedding"),
                "CI cache preparation must use the production filtered adapter");
        assertTrue(
                workflow.contains("MiniLmEmbedding(cache_dir=Path("),
                "CI cache preparation must pass the fixed cache root");
        assertFalse(
                workflow.contains(
                        "SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"),
                "CI must not pollute the snapshot with an unfiltered model-ID load");

        int qualityStart = workflow.indexOf(
                "- name: Run Python retrieval quality");
        int qualityEnd = workflow.indexOf(
                "\n      - name:", qualityStart + 1);
        assertTrue(qualityStart >= 0 && qualityEnd > qualityStart);
        String qualityStep = workflow.substring(qualityStart, qualityEnd);
        assertTrue(
                qualityStep.contains(
                        "HF_HOME: ${{ github.workspace }}/.superpowers/sdd/hf-cache-task9"),
                "CI quality must receive its required fixed HF_HOME");
    }

    @Test
    void acceptanceDocsProvideRunnableQualityCacheEnvironment() throws IOException {
        String docs = readRequired("docs/acceptance/README.md");
        int qualityStart = docs.indexOf("## Retrieval quality");
        int qualityEnd = docs.indexOf("\n## ", qualityStart + 1);
        assertTrue(qualityStart >= 0 && qualityEnd > qualityStart);
        String qualitySection = docs.substring(qualityStart, qualityEnd);

        assertTrue(
                qualitySection.contains(
                        "export HF_HOME=\"${HF_HOME:-$PWD/../../.superpowers/sdd/hf-cache-task9}\""),
                "the documented standalone quality command must set its required HF_HOME");
        assertTrue(
                qualitySection.contains("export HF_HUB_CACHE=\"$HF_HOME\""),
                "the documented standalone quality command must keep both cache variables aligned");
        assertTrue(
                qualitySection.contains("uv run --locked pytest -m quality -q"),
                "the documented standalone quality command must remain locked");
    }

    private static String readRequired(String relativePath) throws IOException {
        Path path = ROOT.resolve(relativePath);
        assertTrue(Files.isRegularFile(path), () -> relativePath + " must exist");
        return Files.readString(path);
    }

    private static void assertBashSyntax(String relativePath) throws Exception {
        Process process = new ProcessBuilder(
                "bash", "-n", ROOT.resolve(relativePath).toString())
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, () -> relativePath + " syntax check timed out");
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(
                0,
                process.exitValue(),
                () -> relativePath + " failed bash -n:\n" + output);
    }
}
