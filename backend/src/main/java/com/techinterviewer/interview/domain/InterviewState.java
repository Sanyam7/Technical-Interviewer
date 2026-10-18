package com.techinterviewer.interview.domain;

/**
 * States of the interview state machine (ARCHITECTURE.md section 5). The constants equal the
 * {@code ck_interviews_state} check (and the from/to state checks of the transition log).
 */
public enum InterviewState {
    INIT,
    INTRODUCTION,
    QUESTIONING,
    FOLLOW_UP,
    CODING,
    SYSTEM_DESIGN,
    FINAL_EVALUATION,
    COMPLETED,
    ABANDONED;

    /** No transition leaves a terminal state. */
    public boolean isTerminal() {
        return this == COMPLETED || this == ABANDONED;
    }

    /** States in which the candidate is being asked questions and may submit an answer. */
    public boolean acceptsAnswers() {
        return switch (this) {
            case INTRODUCTION, QUESTIONING, FOLLOW_UP, CODING, SYSTEM_DESIGN -> true;
            case INIT, FINAL_EVALUATION, COMPLETED, ABANDONED -> false;
        };
    }
}
