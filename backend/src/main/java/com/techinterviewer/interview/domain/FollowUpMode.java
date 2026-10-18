package com.techinterviewer.interview.domain;

/**
 * Why a follow-up question is asked (AI_ARCHITECTURE.md section 2.4); the deterministic follow-up policy
 * decides it, the question generator only words the question. "Move on" is the absence of a follow-up and is
 * not a mode. Stored inside the interview memory (JSON), not in a column with a check.
 */
public enum FollowUpMode {
    /** Probe the highest-weight key point the candidate missed or only partly covered. */
    PROBE_GAP,
    /** Probe a claim the candidate made about their own experience. */
    PROBE_CLAIM,
    /** One step harder on the same topic, after an excellent answer. */
    DEEPEN,
    /**
     * System design only: probe one design dimension (caching, failure handling, ...), the one where the design falls furthest
     * short by weight among those not probed yet (AI_ARCHITECTURE.md section 2.12).
     */
    PROBE_DIMENSION
}
