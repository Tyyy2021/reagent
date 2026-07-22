package com.reagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sharedV1FixturesDeserializeThroughStrictReaders() throws Exception {
        RagSearchRequest request = RagSearchRequest.reader(mapper).readValue(
                Files.readString(Path.of("contracts/rag-search-v1.request.json")));
        RagSearchResponse response = RagSearchResponse.reader(mapper).readValue(
                Files.readString(Path.of("contracts/rag-search-v1.response.json")));

        assertEquals(1, request.contractVersion());
        assertEquals("incident-ops", request.knowledgeBaseId());
        assertEquals(1, response.hits().size());
        assertDoesNotThrow(() -> response.validateFor(request));
    }

    @Test
    void strictReadersRejectUnknownTopLevelAndNestedFields() {
        String unknownRequest = """
                {"contractVersion":1,"knowledgeBaseId":"incident-ops","indexVersion":"v1",
                 "query":"checkout","topK":3,"unexpected":true}
                """;
        String unknownHit = """
                {"contractVersion":1,"indexVersion":"v1","hits":[{
                 "chunkId":"chunk-1","title":"Title","section":"Section",
                 "source":"knowledge/incident-ops/runbooks/a.md","score":0.5,
                 "excerpt":"Excerpt","unexpected":true}]}
                """;

        assertThrows(Exception.class, () -> RagSearchRequest.reader(mapper).readValue(unknownRequest));
        assertThrows(Exception.class, () -> RagSearchResponse.reader(mapper).readValue(unknownHit));
    }

    @Test
    void requestBoundsUseUnicodeCodePointsAndRequireContractV1() {
        String emoji = "\uD83D\uDE80";

        assertDoesNotThrow(() -> new RagSearchRequest(
                1, "k".repeat(64), "v".repeat(64), emoji.repeat(512), 5));
        assertThrows(IllegalArgumentException.class, () -> new RagSearchRequest(
                2, "incident-ops", "v1", "checkout", 3));
        assertThrows(IllegalArgumentException.class, () -> new RagSearchRequest(
                1, "k".repeat(65), "v1", "checkout", 3));
        assertThrows(IllegalArgumentException.class, () -> new RagSearchRequest(
                1, "incident-ops", "v".repeat(65), "checkout", 3));
        assertThrows(IllegalArgumentException.class, () -> new RagSearchRequest(
                1, "incident-ops", "v1", emoji.repeat(513), 3));
        assertThrows(IllegalArgumentException.class, () -> new RagSearchRequest(
                1, "incident-ops", "v1", "checkout", 0));
        assertThrows(IllegalArgumentException.class, () -> new RagSearchRequest(
                1, "incident-ops", "v1", "checkout", 6));
    }

    @Test
    void hitBoundsUseUnicodeCodePointsAndRequireFiniteUnitScore() {
        String emoji = "\uD83D\uDE80";

        assertDoesNotThrow(() -> hit("chunk", emoji.repeat(1200), 1.0,
                "knowledge/incident-ops/runbooks/a.md"));
        assertThrows(IllegalArgumentException.class, () -> hit("chunk", emoji.repeat(1201), 0.5,
                "knowledge/incident-ops/runbooks/a.md"));
        assertThrows(IllegalArgumentException.class, () -> hit("chunk", "excerpt", -0.01,
                "knowledge/incident-ops/runbooks/a.md"));
        assertThrows(IllegalArgumentException.class, () -> hit("chunk", "excerpt", 1.01,
                "knowledge/incident-ops/runbooks/a.md"));
        assertThrows(IllegalArgumentException.class, () -> hit("chunk", "excerpt", Double.NaN,
                "knowledge/incident-ops/runbooks/a.md"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/knowledge/incident-ops/runbooks/a.md",
            "knowledge/incident-ops",
            "knowledge/incident-ops-evil/a.md",
            "knowledge/incident-ops/../secrets.md",
            "knowledge/incident-ops/./runbooks/a.md",
            "knowledge/incident-ops/runbooks\\a.md",
            "knowledge/incident-ops/runbooks/\u0001a.md"
    })
    void sourceMustBeAnExactRepositoryRelativeIncidentOpsDescendant(String source) {
        assertThrows(IllegalArgumentException.class, () -> hit("chunk", "excerpt", 0.5, source));
    }

    @Test
    void responseRequiresUniqueChunksAndValidatesAgainstTheRequest() {
        RagHit first = hit("chunk-1", "first", 0.9,
                "knowledge/incident-ops/runbooks/a.md");
        RagHit second = hit("chunk-2", "second", 0.8,
                "knowledge/incident-ops/runbooks/b.md");

        assertThrows(IllegalArgumentException.class,
                () -> new RagSearchResponse(1, "v1", List.of(first, first)));

        RagSearchResponse response = new RagSearchResponse(1, "v1", List.of(first, second));
        assertThrows(IllegalArgumentException.class,
                () -> response.validateFor(request("v1", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> response.validateFor(request("v2", 2)));
        assertDoesNotThrow(() -> response.validateFor(request("v1", 2)));
    }

    @Test
    void activeIndexResponseIsStrictAndBoundedToIncidentOps() {
        assertDoesNotThrow(() -> new ActiveIndexResponse(1, "incident-ops", "v1", true));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveIndexResponse(2, "incident-ops", "v1", true));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveIndexResponse(1, "other", "v1", true));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveIndexResponse(1, "incident-ops", "v".repeat(65), true));
    }

    private static RagSearchRequest request(String version, int topK) {
        return new RagSearchRequest(1, "incident-ops", version, "checkout", topK);
    }

    private static RagHit hit(String chunkId, String excerpt, double score, String source) {
        return new RagHit(chunkId, "Title", "Section", source, score, excerpt);
    }
}
