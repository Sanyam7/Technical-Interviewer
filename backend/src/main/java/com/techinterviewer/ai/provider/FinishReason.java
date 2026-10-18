package com.techinterviewer.ai.provider;

/** Why a model stopped generating, normalised across providers. Stored in {@code llm_calls.finish_reason}. */
public enum FinishReason {
    /** Natural end of the answer. */
    STOP,
    /** The output token limit cut the answer off: a structured answer is truncated JSON. */
    MAX_TOKENS,
    /** Anything else the provider reports (recitation, malformed response, ...). */
    OTHER
}
