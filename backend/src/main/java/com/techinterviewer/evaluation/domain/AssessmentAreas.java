package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The report's breakdown by assessment area ({@code reports.assessment_areas}, AI_ARCHITECTURE.md section 2.15): what each area
 * scored and on how much evidence, what the interview did not assess, and why the recommendation was capped, if it was.
 *
 * @param areas              every area, in {@link AssessmentArea} order
 * @param skillsNotAssessed  selected skills without an assessed answer
 * @param topicsNotAssessed  topics the plan intended to ask that have no assessed answer
 * @param recommendationCap  why the recommendation is lower than the overall score alone gives; null when it is not capped
 */
public record AssessmentAreas(List<AreaScore> areas, List<Subject> skillsNotAssessed, List<Subject> topicsNotAssessed,
        @Nullable String recommendationCap) {

    public AssessmentAreas {
        areas = Immutables.list(areas);
        skillsNotAssessed = Immutables.list(skillsNotAssessed);
        topicsNotAssessed = Immutables.list(topicsNotAssessed);
        recommendationCap = Require.nullOrNotBlank(recommendationCap, "recommendationCap", 400);
    }

    /**
     * One area's result.
     *
     * @param area                 the area
     * @param planned              the interview's plan meant to assess it
     * @param score                0 to 100; null when nothing in the area could be assessed
     * @param evidenceCount        scored answers behind the score (a design thread counts once); for COMMUNICATION the rated answers
     * @param confidence           how much evidence stands behind the score; null without a score
     * @param shareOfOverall       the part of the overall score's weight that comes from this area, 0 to 1; null for COMMUNICATION, which
     *                             is a rating of every answer rather than a part of the overall score
     * @param lowConfidenceAnswers answers in the area whose evaluation the evaluator itself was unsure of (recommended for human review)
     */
    public record AreaScore(AssessmentArea area, boolean planned, @Nullable Double score, int evidenceCount, @Nullable Confidence confidence,
            @Nullable Double shareOfOverall, int lowConfidenceAnswers) {

        public AreaScore {
            Require.nonNull(area, "area");
            if (score != null) {
                Require.between(score, 0, 100, "score");
            }
            Require.atLeast(evidenceCount, 0, "evidenceCount");
            if ((score == null) != (evidenceCount == 0) || (score == null) != (confidence == null)) {
                throw new IllegalArgumentException("an area has a score and a confidence exactly when it has evidence");
            }
            if (shareOfOverall != null) {
                Require.between(shareOfOverall, 0, 1, "shareOfOverall");
            }
            Require.atLeast(lowConfidenceAnswers, 0, "lowConfidenceAnswers");
        }
    }

    /**
     * A skill or topic, by key and display name.
     *
     * @param key  a skill slug, or a {@code skill/topic} key
     * @param name its display name
     */
    public record Subject(String key, String name) {

        public Subject {
            Require.notBlank(key, "key", 160);
            Require.notBlank(name, "name", 220);
        }
    }
}
