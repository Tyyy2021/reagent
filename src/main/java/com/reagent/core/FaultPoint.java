package com.reagent.core;

/** Stable deterministic crash boundaries shared by runtime and later approval/MCP plans. */
public enum FaultPoint {
    AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL,
    AFTER_APPROVAL_DECIDED_BEFORE_RESUME,
    AFTER_TOOL_MARKED_IN_PROGRESS,
    AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT,
    AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER
}
