package com.reagent.rag;

/** A single bounded citation returned by the RAG service. */
public record RagHit(
        String chunkId,
        String title,
        String section,
        String source,
        double score,
        String excerpt
) {
    private static final String SOURCE_PREFIX = "knowledge/incident-ops/";

    public RagHit {
        chunkId = RagContract.requireCodePoints(chunkId, 1, 255, "chunkId");
        title = RagContract.requireCodePoints(title, 1, 256, "title");
        section = RagContract.requireCodePoints(section, 1, 256, "section");
        source = RagContract.requireCodePoints(source, 1, 512, "source");
        validateSource(source);
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be finite and between 0 and 1");
        }
        excerpt = RagContract.requireCodePoints(excerpt, 0, 1_200, "excerpt");
    }

    private static void validateSource(String source) {
        if (!source.startsWith(SOURCE_PREFIX) || source.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("source must be under the incident knowledge prefix");
        }
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("source contains a control character");
            }
            offset += Character.charCount(codePoint);
        }
        String descendant = source.substring(SOURCE_PREFIX.length());
        for (String segment : descendant.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("source contains an invalid path segment");
            }
        }
    }
}
