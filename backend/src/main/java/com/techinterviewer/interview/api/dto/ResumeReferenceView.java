package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The résumé experience a question is about, shown so the candidate sees what the interviewer read. */
@Schema(description = "The experience from the candidate's own résumé that a personalised question is based on.")
public record ResumeReferenceView(
        @Schema(allowableValues = {"PROJECT", "RESPONSIBILITY", "ACHIEVEMENT"}) String kind,
        @Schema(description = "The statement as the résumé words it.") String text,
        @Schema(description = "The role or project it belongs to, as the résumé names it.") @Nullable String context) {
}
