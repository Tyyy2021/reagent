package com.reagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.Objects;

/** Response from the active-index endpoint for the fixed incident knowledge base. */
public record ActiveIndexResponse(
        int contractVersion,
        String knowledgeBaseId,
        String indexVersion,
        boolean ready
) {
    public ActiveIndexResponse {
        RagContract.requireVersion(contractVersion);
        knowledgeBaseId = RagContract.requireCodePoints(knowledgeBaseId, 1, 64, "knowledgeBaseId");
        if (!"incident-ops".equals(knowledgeBaseId)) {
            throw new IllegalArgumentException("knowledgeBaseId must be incident-ops");
        }
        indexVersion = RagContract.requireCodePoints(indexVersion, 1, 64, "indexVersion");
    }

    public static ObjectReader reader(ObjectMapper mapper) {
        return RagJson.reader(Objects.requireNonNull(mapper, "mapper"), ActiveIndexResponse.class);
    }
}
