package com.reagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.Objects;

/** Versioned request sent to the trusted internal RAG service. */
public record RagSearchRequest(
        int contractVersion,
        String knowledgeBaseId,
        String indexVersion,
        String query,
        int topK
) {
    public RagSearchRequest {
        RagContract.requireVersion(contractVersion);
        knowledgeBaseId = RagContract.requireCodePoints(knowledgeBaseId, 1, 64, "knowledgeBaseId");
        indexVersion = RagContract.requireCodePoints(indexVersion, 1, 64, "indexVersion");
        query = RagContract.requireCodePoints(query, 1, 512, "query");
        if (topK < 1 || topK > 5) {
            throw new IllegalArgumentException("topK must be between 1 and 5");
        }
    }

    public static ObjectReader reader(ObjectMapper mapper) {
        return RagJson.reader(Objects.requireNonNull(mapper, "mapper"), RagSearchRequest.class);
    }
}

final class RagContract {

    private RagContract() {
    }

    static void requireVersion(int contractVersion) {
        if (contractVersion != 1) {
            throw new IllegalArgumentException("contractVersion must be 1");
        }
    }

    static String requireCodePoints(String value, int minimum, int maximum, String field) {
        Objects.requireNonNull(value, field);
        int length = value.codePointCount(0, value.length());
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException(field + " length is outside the contract");
        }
        return value;
    }
}
