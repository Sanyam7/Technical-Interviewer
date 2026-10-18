package com.techinterviewer.interview.domain;

/**
 * A section of the interview plan, declared in the order sections run. The constants equal the
 * {@code ck_questions_section} check.
 */
public enum Section {
    INTRODUCTION(InterviewState.INTRODUCTION),
    QUESTIONING(InterviewState.QUESTIONING),
    CODING(InterviewState.CODING),
    SYSTEM_DESIGN(InterviewState.SYSTEM_DESIGN);

    private final InterviewState state;

    Section(InterviewState state) {
        this.state = state;
    }

    /** The interview state while questions of this section are being asked. */
    public InterviewState state() {
        return state;
    }
}
