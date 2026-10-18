package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "What the interview showed the candidate does not know. Computed without a model from the evaluations' verified "
        + "key-point verdicts and factual errors.")
public record KnowledgeGapsResponse(
        @Schema(description = "Concepts missed or covered only partly, in the order they were asked") List<MissedConceptResponse> missedConcepts,
        @Schema(description = "Wrong statements with their corrections") List<MisconceptionResponse> misconceptions,
        @Schema(description = "Topics answered weakly, weakest first") List<WeakTopicResponse> weakTopics,
        @Schema(description = "Gaps that showed up in more than one question") List<RepeatedMistakeResponse> repeatedMistakes) {

    public KnowledgeGapsResponse {
        missedConcepts = List.copyOf(missedConcepts);
        misconceptions = List.copyOf(misconceptions);
        weakTopics = List.copyOf(weakTopics);
        repeatedMistakes = List.copyOf(repeatedMistakes);
    }
}
