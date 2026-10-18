package com.techinterviewer.evaluation.report.dto;

import com.techinterviewer.evaluation.domain.EvaluationFlag;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A remark about the reliability of one evaluated answer.")
public record IntegrityNoteResponse(
        @Schema(description = "The signal") EvaluationFlag flag,
        @Schema(description = "The question whose answer carried the signal") UUID questionId,
        @Schema(description = "What the reader should know") String note) {
}
