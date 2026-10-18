package com.techinterviewer.ai.agents.output;

import com.techinterviewer.interview.domain.FollowUpMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A follow-up question as the model writes it ({@code schemas/follow-up-question.v1.json}).
 *
 * @param mode              echo of the follow-up decision the policy made; checked against it
 * @param prompt            the follow-up shown to the candidate
 * @param estimatedMinutes  suggested answering time
 * @param expectedKeyPoints what a good answer to the follow-up contains
 */
public record FollowUpDraft(
        @NotNull FollowUpMode mode,
        @NotBlank @Size(min = 15, max = 1000) String prompt,
        @NotNull @Min(1) @Max(15) Integer estimatedMinutes,
        @NotNull @Size(min = 1, max = 4) List<@NotNull @Valid KeyPointDraft> expectedKeyPoints) {
}
