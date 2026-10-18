package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.InterviewState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@Schema(description = "The lightweight view of an interview, for polling: no question, no configuration.")
public record StateResponse(
        InterviewState state,
        ProgressResponse progress,
        @Nullable Instant deadlineAt,
        @Schema(description = "The server's clock; the only clock that counts.") Instant serverTime,
        long version,
        @Schema(description = "The next question is decided but could not be produced yet; call POST /questions/next.")
        boolean awaitingNextQuestion,
        @Schema(description = "The question phase is over: the interview is in its final evaluation or completed.")
        boolean interviewComplete) {
}
