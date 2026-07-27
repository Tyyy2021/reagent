package com.reagent.tool;

import java.util.Objects;

public record ToolExecutionOutcome(Kind kind, String content) {

    public ToolExecutionOutcome {
        kind = Objects.requireNonNull(kind, "kind");
        content = Objects.requireNonNull(content, "content");
    }

    public enum Kind {
        DEFINITIVE,
        REMOTE_OUTCOME_UNKNOWN
    }

    public static ToolExecutionOutcome definitive(String content) {
        return new ToolExecutionOutcome(Kind.DEFINITIVE, content);
    }

    public static ToolExecutionOutcome remoteOutcomeUnknown(String content) {
        return new ToolExecutionOutcome(Kind.REMOTE_OUTCOME_UNKNOWN, content);
    }
}
