package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The evaluation of one answer. It is only shown once the interview is completed.")
public record FeedbackResponse(
        @Schema(description = "0 to 100.") BigDecimal overallScore,
        List<CriterionFeedback> criteria,
        List<KeyPointFeedback> keyPoints,
        String summary,
        @Schema(description = "The test results of a solution to a tested coding problem; absent otherwise.", nullable = true)
        @Nullable CodeTestsFeedback codeTests,
        @Schema(description = "The approach and complexity of a coding solution as the evaluator read them, with the reference "
                + "solution's complexity; absent otherwise.", nullable = true) @Nullable CodeAnalysisFeedback codeAnalysis) {

    public FeedbackResponse {
        criteria = List.copyOf(criteria);
        keyPoints = List.copyOf(keyPoints);
    }

    /** Feedback on an answer that is not a coding solution. */
    public FeedbackResponse(BigDecimal overallScore, List<CriterionFeedback> criteria, List<KeyPointFeedback> keyPoints, String summary) {
        this(overallScore, criteria, keyPoints, summary, null, null);
    }
}
