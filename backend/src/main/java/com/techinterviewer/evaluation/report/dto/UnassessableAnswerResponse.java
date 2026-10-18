package com.techinterviewer.evaluation.report.dto;

import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.Section;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "An answer the platform could not assess. It says nothing about the quality of the answer.")
public record UnassessableAnswerResponse(
        UUID questionId,
        @Schema(description = "Position of the question in the interview, from 1", example = "4") int sequenceNo,
        Section section,
        @Schema(description = "PROVIDER_FAILURE: the evaluation service kept failing. INVALID_OUTPUT: it kept returning unusable results. "
                + "SAFETY_BLOCK: the provider refused to assess the answer.") EvaluationFailure reason) {
}
