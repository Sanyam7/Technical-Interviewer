package com.techinterviewer.ai.routing;

/**
 * The kinds of AI operation, each routed to its own provider, model and settings ({@code app.ai.routes}). Stored in
 * {@code llm_calls.task}.
 */
public enum AiTask {
    /** A root question: technical, coding or system design. */
    QUESTION_GENERATION,
    /** A follow-up aimed at a gap, a claim or a deeper angle chosen by the follow-up policy. */
    FOLLOW_UP_GENERATION,
    /** Rating one answer against its rubric and key points. */
    ANSWER_EVALUATION,
    /** The narrative of the final report. */
    REPORT_WRITING,
    /** The structured profile of an uploaded résumé (AI_ARCHITECTURE.md 2.13). */
    RESUME_EXTRACTION
}
