package com.reagent.rag;

import java.util.Objects;

/** Bounded, non-secret failure exposed at the Java RAG boundary. */
public final class RagContractException extends RuntimeException {

    private static final int MAX_MESSAGE_CODE_POINTS = 256;

    private final String code;

    public RagContractException(String code, String message) {
        super(bound(message));
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }

    private static String bound(String message) {
        String safe = Objects.requireNonNull(message, "message").codePoints()
                .map(codePoint -> Character.isISOControl(codePoint) ? ' ' : codePoint)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        int count = safe.codePointCount(0, safe.length());
        if (count <= MAX_MESSAGE_CODE_POINTS) {
            return safe;
        }
        return safe.substring(0, safe.offsetByCodePoints(0, MAX_MESSAGE_CODE_POINTS));
    }
}
