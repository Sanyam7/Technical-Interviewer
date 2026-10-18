package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A strength or an improvement area with the answers that back it. Every item cites at least one answer of this interview.")
public record NarrativeItemResponse(
        @Schema(description = "The statement") String text,
        @Schema(description = "The answers that show it") List<EvidenceResponse> evidence) {

    public NarrativeItemResponse {
        evidence = List.copyOf(evidence);
    }
}
