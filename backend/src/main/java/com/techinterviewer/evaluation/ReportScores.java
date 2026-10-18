package com.techinterviewer.evaluation;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.UnassessableAnswer;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything the final report states as a number, computed by {@link ReportAggregator}. All scores are 0 to 100 with
 * two decimals.
 *
 * @param overallScore      difficulty-weighted score over the technical evidence; null when there is insufficient
 *                          evidence (no question other than the introduction was answered)
 * @param overallConfidence confidence of the overall score; LOW when there is no evidence
 * @param sections          score per section that has evidence, in running order (the introduction is listed but does
 *                          not enter the overall score)
 * @param skills            score per skill, ordered by skill slug
 * @param topics            score per topic, ordered by key
 * @param coverage          how much of the interview the candidate got through
 * @param integrityNotes    one note per integrity flag that occurred, in flag order
 * @param assessment        how much of the intended assessment weight could be assessed, and which answers could not
 * @param areas             one result per assessment area, in {@link AssessmentArea} order (AI_ARCHITECTURE.md section 2.15)
 */
public record ReportScores(
        @Nullable Double overallScore,
        Confidence overallConfidence,
        List<SectionScore> sections,
        List<SkillScore> skills,
        List<TopicScore> topics,
        Coverage coverage,
        List<IntegrityNote> integrityNotes,
        Assessment assessment,
        List<AreaResult> areas) {

    public ReportScores {
        if (overallScore != null) {
            Require.between(overallScore, 0, 100, "overallScore");
        }
        Require.nonNull(overallConfidence, "overallConfidence");
        sections = Immutables.list(sections);
        skills = Immutables.list(skills);
        topics = Immutables.list(topics);
        Require.nonNull(coverage, "coverage");
        integrityNotes = Immutables.list(integrityNotes);
        Require.nonNull(assessment, "assessment");
        areas = Immutables.list(areas);
    }

    /** Scores without an area breakdown. */
    public ReportScores(
            @Nullable Double overallScore,
            Confidence overallConfidence,
            List<SectionScore> sections,
            List<SkillScore> skills,
            List<TopicScore> topics,
            Coverage coverage,
            List<IntegrityNote> integrityNotes,
            Assessment assessment) {
        this(overallScore, overallConfidence, sections, skills, topics, coverage, integrityNotes, assessment, List.of());
    }

    /** The result of {@code area}; empty when the scores have no area breakdown. */
    public java.util.Optional<AreaResult> area(AssessmentArea area) {
        return areas.stream().filter(result -> result.area() == area).findFirst();
    }

    /** Scores of an interview in which every answer could be assessed. */
    public ReportScores(
            @Nullable Double overallScore,
            Confidence overallConfidence,
            List<SectionScore> sections,
            List<SkillScore> skills,
            List<TopicScore> topics,
            Coverage coverage,
            List<IntegrityNote> integrityNotes) {
        this(overallScore, overallConfidence, sections, skills, topics, coverage, integrityNotes, Assessment.COMPLETE, List.of());
    }

    /** True when there is not enough evidence to state an overall score or a recommendation. */
    public boolean insufficientEvidence() {
        return overallScore == null;
    }

    /**
     * What part of the interview the scores could be based on.
     *
     * @param intendedWeight assessment weight (the difficulty levels) of the technical turns of the interview
     * @param assessedWeight the part of it that could be assessed; skipped root questions count (they score 0)
     * @param unassessable   the answers that could not be assessed, in the order they were asked; none of them is in any score
     */
    public record Assessment(int intendedWeight, int assessedWeight, List<UnassessableAnswer> unassessable) {

        /** Nothing was unassessable. */
        public static final Assessment COMPLETE = new Assessment(0, 0, List.of());

        public Assessment {
            Require.atLeast(assessedWeight, 0, "assessedWeight");
            Require.atLeast(intendedWeight, assessedWeight, "intendedWeight");
            unassessable = Immutables.list(unassessable);
        }
    }

    /**
     * The result of one assessment area (AI_ARCHITECTURE.md section 2.15).
     *
     * @param score                difficulty-weighted mean of the area's scored turns (COMMUNICATION: the mean rating times 25); null
     *                             without evidence
     * @param evidenceCount        turns behind the score (COMMUNICATION: rated answers)
     * @param confidence           by the number of turns and their spread, like a skill; null without evidence
     * @param shareOfOverall       the area's part of the weight behind the overall score, 0 to 1 with two decimals; null for
     *                             COMMUNICATION and when there is no overall evidence
     * @param lowConfidenceAnswers the area's answers whose evaluation ended with low evaluator confidence
     */
    public record AreaResult(AssessmentArea area, @Nullable Double score, int evidenceCount, @Nullable Confidence confidence,
            @Nullable Double shareOfOverall, int lowConfidenceAnswers) {

        public AreaResult {
            Require.nonNull(area, "area");
            if (score != null) {
                Require.between(score, 0, 100, "score");
            }
            Require.atLeast(evidenceCount, 0, "evidenceCount");
            Require.atLeast(lowConfidenceAnswers, 0, "lowConfidenceAnswers");
        }
    }

    /**
     * Score of one section.
     *
     * @param evidenceCount scored turns that make up the score (a design thread counts once)
     */
    public record SectionScore(Section section, double score, int evidenceCount) {

        public SectionScore {
            Require.nonNull(section, "section");
            Require.between(score, 0, 100, "score");
            Require.atLeast(evidenceCount, 1, "evidenceCount");
        }
    }

    /**
     * Score of one skill ({@code report_skill_scores}).
     *
     * @param averageDifficulty mean difficulty level of the evidence, 1 to 5
     */
    public record SkillScore(String skill, double score, Confidence confidence, int evidenceCount, double averageDifficulty) {

        public SkillScore {
            Require.notBlank(skill, "skill", 60);
            Require.between(score, 0, 100, "score");
            Require.nonNull(confidence, "confidence");
            Require.atLeast(evidenceCount, 1, "evidenceCount");
            Require.between(averageDifficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "averageDifficulty");
        }
    }

    /**
     * Score of one topic.
     *
     * @param topic {@code skill/topic} key
     */
    public record TopicScore(String topic, double score, int evidenceCount, double averageDifficulty) {

        public TopicScore {
            Require.notBlank(topic, "topic", 160);
            Require.between(score, 0, 100, "score");
            Require.atLeast(evidenceCount, 1, "evidenceCount");
            Require.between(averageDifficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "averageDifficulty");
        }
    }

    /**
     * Coverage numbers.
     *
     * @param questionsAnswered answers that were not skipped, follow-ups and the introduction included
     * @param skipped           skipped answers
     * @param followUps         answers to follow-up questions, skipped ones included (they are part of the two numbers above)
     * @param topicsCovered     distinct topics with at least one answer that was not skipped
     * @param skillsCovered     distinct skills with at least one answer that was not skipped
     */
    public record Coverage(int questionsAnswered, int skipped, int followUps, int topicsCovered, int skillsCovered) {

        public Coverage {
            Require.atLeast(questionsAnswered, 0, "questionsAnswered");
            Require.atLeast(skipped, 0, "skipped");
            Require.atLeast(followUps, 0, "followUps");
            Require.atLeast(topicsCovered, 0, "topicsCovered");
            Require.atLeast(skillsCovered, 0, "skillsCovered");
        }
    }

    /**
     * An integrity or quality signal that occurred in the interview.
     *
     * @param flag        the flag
     * @param answerCount answers carrying it
     * @param questionIds the questions of those answers, in the order they were asked
     * @param message     a fixed sentence describing the flag
     */
    public record IntegrityNote(EvaluationFlag flag, int answerCount, List<UUID> questionIds, String message) {

        public IntegrityNote {
            Require.nonNull(flag, "flag");
            questionIds = Immutables.list(questionIds);
            Require.atLeast(answerCount, 1, "answerCount");
            if (questionIds.size() != answerCount) {
                throw new IllegalArgumentException("answerCount must equal the number of question ids");
            }
            Require.notBlank(message, "message");
        }
    }
}
