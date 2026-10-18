package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * How much of the interview the scores of a report stand on, and which answers could not be assessed.
 */
@Schema(description = "How much of the interview the scores stand on. Answers that could not be assessed are not scored (they are not "
        + "counted as zero); they are listed here. outcome COMPLETE has none, PARTIAL has some, INCONCLUSIVE too many for a verdict.")
public record AssessmentResponse(
        @Schema(description = "Share of the intended assessment weight (the difficulty levels of the technical questions) that could be "
                + "assessed, 0 to 1 with two decimals, rounded down. 1 when every answer was assessed.", example = "0.85") double assessedShare,
        @Schema(description = "Assessment weight that could be assessed", example = "17") int assessedWeight,
        @Schema(description = "Assessment weight of the technical questions of the interview", example = "20") int intendedWeight,
        List<UnassessableAnswerResponse> unassessableAnswers) {

    public AssessmentResponse {
        unassessableAnswers = List.copyOf(unassessableAnswers);
    }
}
