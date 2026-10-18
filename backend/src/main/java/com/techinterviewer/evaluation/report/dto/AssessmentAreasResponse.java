package com.techinterviewer.evaluation.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The report by assessment area: fundamentals, practical application, problem solving, project depth and communication; "
        + "what the interview meant to assess and could not; and why the recommendation was capped, if it was.")
public record AssessmentAreasResponse(
        @Schema(description = "Every area, in the order FUNDAMENTALS, PRACTICAL, PROBLEM_SOLVING, PROJECT, COMMUNICATION") List<AreaScoreResponse> areas,
        @Schema(description = "Selected technologies without an assessed answer; empty for coding and system design interviews, which ask no "
                + "topic questions") List<SubjectResponse> skillsNotAssessed,
        @Schema(description = "Topics the plan meant to ask that have no assessed answer (not reached, skipped or not assessable)")
        List<SubjectResponse> topicsNotAssessed,
        @Schema(description = "Why the recommendation is lower than the overall score alone gives: weak fundamentals cap it at DEVELOPING. "
                + "Null when it is not capped", nullable = true) @Nullable String recommendationCap) {

    public AssessmentAreasResponse {
        areas = List.copyOf(areas);
        skillsNotAssessed = List.copyOf(skillsNotAssessed);
        topicsNotAssessed = List.copyOf(topicsNotAssessed);
    }
}
