package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.InterviewState;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * What the candidate gets back for an answer (or a skip): where the interview stands and the next question. It never
 * says how the answer was judged: no score, no evaluation, no key point, no difficulty.
 */
@Schema(description = "The result of a turn: the interview after the answer was processed.")
public record AnswerResponse(
        @Schema(description = "The answer (or skip) is part of the interview. Always true on a 200: a resubmission of an answered "
                + "question is an idempotent retry and gets the current state without being evaluated again.") boolean accepted,
        @Schema(description = "The state of the interview after the turn.") InterviewState state,
        ProgressResponse progress,
        @Schema(description = "The question to answer next; absent when the interview is over or the next question is not ready.", nullable = true)
        @Nullable QuestionResponse nextQuestion,
        @Schema(description = "The answer was accepted but the next question could not be produced yet: call POST /questions/next, "
                + "or reload the session. Also true for a moment while a concurrent request is still processing the same answer.")
        boolean awaitingNextQuestion,
        @Schema(description = "The question phase is over: finish the interview with POST /finish to get the report.")
        boolean interviewComplete) {
}
