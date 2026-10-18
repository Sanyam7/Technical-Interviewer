package com.techinterviewer.evaluation.report;

import com.techinterviewer.evaluation.domain.AssessmentAreas;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.IntegrityNote;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.SectionScore;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.EvaluationDigest;
import com.techinterviewer.interview.port.NarrativeRequest;
import com.techinterviewer.interview.port.SkillScoreSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Everything of a report that is computed without a model: the numbers of the report, the rows of the skill scores and
 * the inputs of the narrator.
 *
 * @param outcome        COMPLETE, PARTIAL or INCONCLUSIVE; an INCONCLUSIVE report has neither an overall score nor a recommendation
 * @param overallScore   null when there is no evidence outside the introduction, and when the outcome is INCONCLUSIVE
 * @param recommendation null when there is no overall score or the confidence is too low for a band
 * @param skills         one row per skill with evidence, ordered by skill slug
 * @param digests        one digest per evaluated answer, in the order the questions were asked
 * @param knowledgeGaps  what the evaluations showed the candidate does not know
 * @param assessmentAreas the breakdown by assessment area, what was not assessed and why the recommendation was capped
 */
record ComposedReport(
        ReportOutcome outcome,
        @Nullable BigDecimal overallScore,
        @Nullable Recommendation recommendation,
        List<SectionScore> sections,
        List<SkillRow> skills,
        List<IntegrityNote> integrityNotes,
        Coverage coverage,
        List<EvaluationDigest> digests,
        KnowledgeGapReport knowledgeGaps,
        AssessmentAreas assessmentAreas) {

    ComposedReport {
        sections = List.copyOf(sections);
        skills = List.copyOf(skills);
        integrityNotes = List.copyOf(integrityNotes);
        digests = List.copyOf(digests);
        Objects.requireNonNull(knowledgeGaps, "knowledgeGaps");
        Objects.requireNonNull(assessmentAreas, "assessmentAreas");
    }

    /** One skill with the data of {@code report_skill_scores}. */
    record SkillRow(
            long skillId,
            String slug,
            String name,
            BigDecimal score,
            Confidence confidence,
            int evidenceCount,
            BigDecimal averageDifficulty) {
    }

    /** The ids of the evaluations the report is built from: the only ones a narrative may cite. */
    Set<UUID> evaluationIds() {
        return digests.stream().map(EvaluationDigest::evaluationId).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The input of the narrator. Empty when the report has no recommendation: a narrative about an interview that the
     * numbers do not support would be false precision.
     */
    Optional<NarrativeRequest> narrativeRequest(UUID interviewId, TargetRole role, ExperienceLevel experience, InterviewType type) {
        if (recommendation == null || overallScore == null) {
            return Optional.empty();
        }
        List<SkillScoreSummary> summaries = skills.stream()
                .map(skill -> new SkillScoreSummary(
                        skill.slug(), skill.name(), skill.score().doubleValue(), skill.confidence(), skill.evidenceCount()))
                .toList();
        return Optional.of(new NarrativeRequest(
                interviewId, role, experience, type, overallScore.doubleValue(), recommendation, summaries, digests,
                coverage.assessmentCoverage().doubleValue(), coverage.unassessable().size()));
    }
}
