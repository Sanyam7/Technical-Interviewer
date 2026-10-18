package com.techinterviewer.ai.agents.output;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.FollowUpMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A probe of a system design dimension as the model writes it ({@code schemas/design-probe.v1.json}, the criterion narrowed per
 * request to the one the engine chose).
 *
 * @param mode             always PROBE_DIMENSION, echoed
 * @param criterion        the probed dimension, echoed
 * @param prompt           the question
 * @param estimatedMinutes how long a strong answer takes
 */
public record DesignProbeDraft(
        @NotNull FollowUpMode mode,
        @NotNull Criterion criterion,
        @NotBlank @Size(max = 600) String prompt,
        @NotNull @Min(1) @Max(15) Integer estimatedMinutes) {
}
