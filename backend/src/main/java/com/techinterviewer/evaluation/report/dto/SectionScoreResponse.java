package com.techinterviewer.evaluation.report.dto;

import com.techinterviewer.interview.domain.Section;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Score of one interview section, 0 to 100. The introduction is rated on communication only and does not enter the overall score.")
public record SectionScoreResponse(
        @Schema(description = "The section") Section section,
        @Schema(description = "0 to 100, two decimals", example = "76.0") double score) {
}
