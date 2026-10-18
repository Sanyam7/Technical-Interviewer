package com.techinterviewer.ai.observability;

/** Outcome of one model call. The constants equal the {@code ck_llm_calls_status} check. */
public enum LlmCallStatus {
    /** Accepted on the first structured attempt. */
    OK,
    /** Accepted on the repair attempt. */
    REPAIRED,
    /** The provider answered, but the output was rejected (parse, schema, validation or semantic check). */
    INVALID_OUTPUT,
    RATE_LIMITED,
    TIMEOUT,
    /** The provider refused the content for safety reasons. */
    BLOCKED,
    /** Any other failure: provider unreachable or failing, request rejected. */
    ERROR,
    /** Reserved for a provider-level fallback (not used: fallback is off by design). */
    FALLBACK
}
