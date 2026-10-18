package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.List;
import java.util.UUID;

/**
 * Input of the report narrator (AI_ARCHITECTURE.md section 2.8): the deterministic results and the evaluation
 * records, nothing else. It is only built for an interview with at least one evaluated answer.
 *
 * @param interviewId    the interview
 * @param role           role interviewed for
 * @param experience     seniority of the candidate
 * @param type           interview type
 * @param overallScore   computed overall score, 0 to 100
 * @param recommendation computed recommendation band
 * @param skillScores    computed per-skill scores
 * @param evaluations    one digest per evaluated answer; the narrator may cite only these ids
 * @param assessmentCoverage share of the intended assessment weight that was assessed, 0 to 1 (1 when every answer was assessed). Below 1
 *                       some answers could not be assessed and are not in the scores: the narrative must not describe them
 * @param unassessableAnswers how many answers could not be assessed
 */
public record NarrativeRequest(
        UUID interviewId,
        TargetRole role,
        ExperienceLevel experience,
        InterviewType type,
        double overallScore,
        Recommendation recommendation,
        List<SkillScoreSummary> skillScores,
        List<EvaluationDigest> evaluations,
        double assessmentCoverage,
        int unassessableAnswers) {

    public NarrativeRequest {
        Require.nonNull(interviewId, "interviewId");
        Require.nonNull(role, "role");
        Require.nonNull(experience, "experience");
        Require.nonNull(type, "type");
        Require.between(overallScore, 0, 100, "overallScore");
        Require.nonNull(recommendation, "recommendation");
        Require.between(assessmentCoverage, 0, 1, "assessmentCoverage");
        Require.atLeast(unassessableAnswers, 0, "unassessableAnswers");
        skillScores = Immutables.list(skillScores);
        evaluations = Immutables.list(evaluations);
        if (evaluations.isEmpty()) {
            throw new IllegalArgumentException("evaluations must not be empty: there is nothing to narrate");
        }
    }

    /** A request for an interview in which every answer was assessed. */
    public NarrativeRequest(
            UUID interviewId,
            TargetRole role,
            ExperienceLevel experience,
            InterviewType type,
            double overallScore,
            Recommendation recommendation,
            List<SkillScoreSummary> skillScores,
            List<EvaluationDigest> evaluations) {
        this(interviewId, role, experience, type, overallScore, recommendation, skillScores, evaluations, 1.0, 0);
    }
}
