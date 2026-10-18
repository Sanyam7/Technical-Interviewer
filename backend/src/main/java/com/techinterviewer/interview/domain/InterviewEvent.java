package com.techinterviewer.interview.domain;

/**
 * Events that drive the interview state machine and are recorded in {@code interview_state_transitions.event}
 * (a free {@code varchar(30)}: the schema has no check, so adding a constant needs no migration).
 */
public enum InterviewEvent {
    /** Interview created: the first transition row, from nothing to INIT. */
    CREATE,
    /** The candidate starts the interview: INIT to INTRODUCTION; the deadline clock starts. */
    START,
    /** The introduction was answered (and evaluated or skipped): to the first section after it. */
    INTRO_ANSWERED,
    /** An answer was processed and the next question stays in the same section or becomes a follow-up. */
    ANSWER_EVALUATED,
    /** The current section ran out of slots or time: next section, or FINAL_EVALUATION after the last. */
    SECTION_COMPLETE,
    /** The follow-up thread ended: back to the section recorded in {@code resume_state}. */
    FOLLOW_UP_DONE,
    /** The deadline passed (computed lazily on the server). */
    TIME_UP,
    /** The candidate ended the interview. */
    END_REQUESTED,
    /** The report was generated: FINAL_EVALUATION to COMPLETED. */
    REPORT_READY,
    /** Report generation failed (retryable): FINAL_EVALUATION stays FINAL_EVALUATION. */
    REPORT_FAILED,
    /** The candidate cancelled before starting: INIT to ABANDONED. */
    ABANDON
}
