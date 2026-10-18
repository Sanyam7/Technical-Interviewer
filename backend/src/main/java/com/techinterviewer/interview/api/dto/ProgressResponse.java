package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.Section;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "Where the candidate is in the interview. Counts are section scoped.")
public record ProgressResponse(
        @Schema(description = "The section being asked (for a follow-up: the section it belongs to). Once the question phase is over: "
                + "the last section in which a question was asked. Absent before the interview was started.", nullable = true)
        @Nullable Section section,
        @Schema(description = "Zero-based position of the section in the plan; absent when section is.", nullable = true)
        @Nullable Integer sectionIndex,
        @Schema(description = "Number of sections of the plan.") int sectionCount,
        @Schema(description = "Main questions of the section that were answered or skipped; follow-ups are not counted.") int questionsAnswered,
        @Schema(description = "Main questions the plan has for the section; absent when section is.", nullable = true)
        @Nullable Integer plannedQuestionsInSection,
        @Schema(description = "Seconds until the deadline: the whole duration before the start, 0 once the question phase is over.")
        long remainingSeconds) {
}
