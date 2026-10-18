package com.techinterviewer.interview.domain;

/**
 * What kind of questioning question the engine asks (AI_ARCHITECTURE.md section 2.15). Every type belongs to one stage; the
 * engine picks the type, the question generator writes a question of that type, and the report counts the answer in the
 * assessment area of its type.
 */
public enum QuestionType {

    /** What a concept is for, when to use it and how it compares with the alternatives. */
    CONCEPT(InterviewStage.FUNDAMENTALS),
    /** How it works under the hood and what follows from that. */
    INTERNALS(InterviewStage.FUNDAMENTALS),
    /** A realistic situation in which the candidate applies the concept: choose, design or implement. */
    SCENARIO(InterviewStage.PRACTICAL),
    /** A symptom to diagnose: what is wrong, how to find out and how to fix it. */
    DEBUGGING(InterviewStage.PRACTICAL),
    /** The candidate's own work, from the résumé or from what they said in the interview. */
    EXPERIENCE(InterviewStage.PROJECT);

    private final InterviewStage stage;

    QuestionType(InterviewStage stage) {
        this.stage = stage;
    }

    public InterviewStage stage() {
        return stage;
    }
}
