package com.techinterviewer.interview.api.dto;

/** Why the client calls finish. */
public enum FinishReason {
    /** The candidate ends the interview early. */
    CANDIDATE_ENDED,
    /** The question phase is over (plan complete or time up) and the client asks for the report. */
    ACKNOWLEDGE_COMPLETE
}
