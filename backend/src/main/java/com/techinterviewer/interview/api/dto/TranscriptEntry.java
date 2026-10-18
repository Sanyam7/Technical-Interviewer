package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "A question with its answer and, once the interview is completed, its feedback.")
public record TranscriptEntry(
        QuestionResponse question,
        @Schema(description = "Absent when the question was not answered.", nullable = true) @Nullable AnswerView answer,
        @Schema(description = "Present only when the interview is COMPLETED and the answer was evaluated.", nullable = true)
        @Nullable FeedbackResponse feedback) {
}
