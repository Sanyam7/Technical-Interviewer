package com.techinterviewer.evaluation.report.dto;

import com.techinterviewer.evaluation.domain.KnowledgeGapReport.RepeatedMistake;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Schema(description = "One gap shown in more than one question.")
public record RepeatedMistakeResponse(
        @Schema(description = "What was repeated, in the words of its first occurrence") String description,
        @Schema(description = "MISCONCEPTION: a wrong belief stated again; MISSED_CONCEPT: the same concept missing each time") RepeatedMistake.Kind kind,
        @Schema(description = "How the occurrences were linked: EVALUATOR_LINK (the evaluator said a mistake repeats an earlier one), "
                + "SHARED_SOURCE (the gaps cite the same knowledge-base section)") Set<RepeatedMistake.Basis> basis,
        @Schema(description = "The questions it occurred in, in the order they were asked; at least two") List<UUID> questionIds,
        @Schema(description = "Topics of those questions") List<GapTopicResponse> topics) {

    public RepeatedMistakeResponse {
        basis = Set.copyOf(basis);
        questionIds = List.copyOf(questionIds);
        topics = List.copyOf(topics);
    }
}
