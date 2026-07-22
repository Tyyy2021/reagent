package com.reagent.rag;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned bounded response returned by the RAG service. */
public record RagSearchResponse(
        int contractVersion,
        String indexVersion,
        List<RagHit> hits
) {
    public RagSearchResponse {
        RagContract.requireVersion(contractVersion);
        indexVersion = RagContract.requireCodePoints(indexVersion, 1, 64, "indexVersion");
        hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
        if (hits.size() > 5) {
            throw new IllegalArgumentException("hits exceeds the contract maximum");
        }
        Set<String> chunkIds = new HashSet<>();
        for (RagHit hit : hits) {
            if (!chunkIds.add(hit.chunkId())) {
                throw new IllegalArgumentException("chunkId values must be unique");
            }
        }
    }

    public static ObjectReader reader(ObjectMapper mapper) {
        return RagJson.reader(Objects.requireNonNull(mapper, "mapper"), RagSearchResponse.class);
    }

    /** Applies the request-relative invariants that cannot be represented by the response alone. */
    public void validateFor(RagSearchRequest request) {
        Objects.requireNonNull(request, "request");
        if (!indexVersion.equals(request.indexVersion())) {
            throw new IllegalArgumentException("response indexVersion does not match the request");
        }
        if (hits.size() > request.topK()) {
            throw new IllegalArgumentException("response hit count exceeds requested topK");
        }
    }
}

final class RagJson {

    private RagJson() {
    }

    static ObjectReader reader(ObjectMapper applicationMapper, Class<?> targetType) {
        ObjectMapper contractMapper = applicationMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        return contractMapper.readerFor(targetType);
    }
}
