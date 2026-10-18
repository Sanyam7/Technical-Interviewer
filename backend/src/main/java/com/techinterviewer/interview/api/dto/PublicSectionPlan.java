package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.Section;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One section of the interview as the candidate sees it: no topic quotas, no difficulty.")
public record PublicSectionPlan(
        Section section,
        @Schema(description = "Time budget of the section in minutes. It is soft: a question in progress always finishes.") int minutes,
        @Schema(description = "How many main questions the section asks. Follow-up questions come on top.") int plannedQuestions) {
}
