package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.InterviewState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "Everything a client needs to show or resume an interview.")
public record SessionResponse(
        UUID id,
        InterviewState state,
        @Schema(description = "The server's clock; the only clock that counts.") Instant serverTime,
        @Nullable Instant startedAt,
        @Nullable Instant deadlineAt,
        @Schema(description = "Why the question phase ended; absent while it runs.", nullable = true) @Nullable EndReason endReason,
        ProgressResponse progress,
        @Schema(description = "The question to answer now; absent when none is open.", nullable = true) @Nullable QuestionResponse currentQuestion,
        @Schema(description = "The interview accepts answers, has no open question and the next one is decided but not produced yet: "
                + "call POST /questions/next.") boolean awaitingNextQuestion,
        @Schema(description = "The question phase is over: the interview is in its final evaluation or completed.") boolean interviewComplete,
        @Schema(description = "Increases with every change of the interview.") long version,
        ConfigurationResponse configuration) {
}
