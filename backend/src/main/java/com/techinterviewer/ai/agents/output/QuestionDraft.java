package com.techinterviewer.ai.agents.output;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A root question as the model writes it ({@code schemas/question.v2.json}, {@code schemas/coding-question.v2.json}, {@code schemas/resume-question.v1.json}).
 * The topic and difficulty are echoes of what was requested; the agent checks them and then uses its own values.
 *
 * @param topic             the requested topic key, or {@code none}
 * @param difficulty        the requested level, 1 to 5
 * @param prompt            the question shown to the candidate
 * @param estimatedMinutes  suggested answering time
 * @param expectedKeyPoints what a good answer contains (server-only)
 * @param coding            the material of a coding problem; only for coding questions
 * @param resumeQuote       the words of the résumé experience the question is about; only for a question anchored on one
 */
public record QuestionDraft(
        @NotBlank @Size(max = 160) String topic,
        @NotNull @Min(1) @Max(5) Integer difficulty,
        @NotBlank @Size(min = 40, max = 2000) String prompt,
        @NotNull @Min(1) @Max(60) Integer estimatedMinutes,
        @NotNull @Size(min = 3, max = 6) List<@NotNull @Valid KeyPointDraft> expectedKeyPoints,
        @Valid @Nullable CodingDraft coding,
        @Nullable @Size(max = 300) String resumeQuote) {
}
