package com.techinterviewer.evaluation.report;

import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.evaluation.domain.AssessmentAreas;
import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.IntegrityNote;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport;
import com.techinterviewer.evaluation.domain.NarrativeEntry;
import com.techinterviewer.evaluation.domain.ReportNarrativeDocument;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.domain.SectionScore;
import com.techinterviewer.evaluation.report.dto.AreaScoreResponse;
import com.techinterviewer.evaluation.report.dto.AssessmentAreasResponse;
import com.techinterviewer.evaluation.report.dto.AssessmentResponse;
import com.techinterviewer.evaluation.report.dto.CoverageResponse;
import com.techinterviewer.evaluation.report.dto.EvidenceResponse;
import com.techinterviewer.evaluation.report.dto.GapTopicResponse;
import com.techinterviewer.evaluation.report.dto.IntegrityNoteResponse;
import com.techinterviewer.evaluation.report.dto.KnowledgeGapsResponse;
import com.techinterviewer.evaluation.report.dto.MisconceptionResponse;
import com.techinterviewer.evaluation.report.dto.MissedConceptResponse;
import com.techinterviewer.evaluation.report.dto.NarrativeItemResponse;
import com.techinterviewer.evaluation.report.dto.ReadingResponse;
import com.techinterviewer.evaluation.report.dto.RepeatedMistakeResponse;
import com.techinterviewer.evaluation.report.dto.ReportResponse;
import com.techinterviewer.evaluation.report.dto.SectionScoreResponse;
import com.techinterviewer.evaluation.report.dto.SkillScoreResponse;
import com.techinterviewer.evaluation.report.dto.SubjectResponse;
import com.techinterviewer.evaluation.report.dto.UnassessableAnswerResponse;
import com.techinterviewer.evaluation.report.dto.WeakTopicResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Explicit mapping of a stored report to its response. Entities stop here: nothing in the response is an entity, and
 * nothing in it comes from the questions' expected key points or the rubrics.
 */
final class ReportResponseMapper {

    /** The longest excerpt of an answer shown as evidence. */
    static final int MAX_EXCERPT_LENGTH = 240;

    private static final String ELLIPSIS = "...";

    private ReportResponseMapper() {
    }

    /** The report of an interview that has ended but whose generation has not started. */
    static ReportResponse notStarted(UUID interviewId) {
        return new ReportResponse(
                interviewId, ReportStatus.PENDING, null, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null, null, null, null, 0, true, null);
    }

    /**
     * @param skills   the skill rows of the report, best score first
     * @param skillRefs the catalog entries of those skills
     * @param evidence the answer behind each evaluation id the report cites
     */
    static ReportResponse of(
            InterviewReport report,
            List<ReportSkillScore> skills,
            Map<Long, SkillRef> skillRefs,
            Map<UUID, EvidenceResponse> evidence) {
        ReportStatus status = report.getStatus();
        ReportNarrativeDocument narrative = report.getNarrative();
        return new ReportResponse(
                report.getInterviewId(),
                status,
                status == ReportStatus.READY ? report.getOutcome() : null,
                report.getOverallScore() == null ? null : report.getOverallScore().doubleValue(),
                report.getRecommendation(),
                sections(report),
                skills(skills, skillRefs),
                narrative == null ? null : narrative.summary(),
                narrative == null ? List.of() : items(narrative.strengths(), evidence),
                narrative == null ? List.of() : items(narrative.improvementAreas(), evidence),
                narrative == null ? List.of() : narrative.studyPlan(),
                narrative == null ? List.of() : narrative.furtherReading().stream()
                        .map(entry -> new ReadingResponse(entry.title(), entry.section(), entry.source())).toList(),
                integrityNotes(report, evidence),
                report.getCoverage() == null
                        ? null
                        : new CoverageResponse(
                                report.getCoverage().questionsAnswered(),
                                report.getCoverage().skipped(),
                                report.getCoverage().followUps(),
                                report.getCoverage().minutesUsed()),
                assessment(report),
                status == ReportStatus.READY ? knowledgeGaps(report.getKnowledgeGaps()) : null,
                status == ReportStatus.READY ? assessmentAreas(report.getAssessmentAreas()) : null,
                report.getGeneratedAt(),
                report.getAttempts(),
                status == ReportStatus.PENDING || status == ReportStatus.FAILED,
                report.getErrorMessage());
    }

    private static @Nullable KnowledgeGapsResponse knowledgeGaps(@Nullable KnowledgeGapReport gaps) {
        if (gaps == null) {
            return null;
        }
        return new KnowledgeGapsResponse(
                gaps.missedConcepts().stream().map(gap -> new MissedConceptResponse(gap.concept(), gap.status(), topic(gap.topic()), gap.questionId())).toList(),
                gaps.misconceptions().stream()
                        .map(error -> new MisconceptionResponse(error.claim(), error.correction(), topic(error.topic()), error.questionId())).toList(),
                gaps.weakTopics().stream().map(weak -> new WeakTopicResponse(topic(weak.topic()), weak.score(), weak.evidenceCount())).toList(),
                gaps.repeatedMistakes().stream().map(repeated -> new RepeatedMistakeResponse(repeated.description(), repeated.kind(),
                        repeated.basis(), repeated.questionIds(), repeated.topics().stream().map(ReportResponseMapper::topic).toList())).toList());
    }

    private static @Nullable AssessmentAreasResponse assessmentAreas(@Nullable AssessmentAreas areas) {
        if (areas == null) {
            return null;
        }
        return new AssessmentAreasResponse(
                areas.areas().stream().map(area -> new AreaScoreResponse(area.area(), area.planned(), area.score(), area.evidenceCount(),
                        area.confidence(), area.shareOfOverall(), area.lowConfidenceAnswers())).toList(),
                areas.skillsNotAssessed().stream().map(subject -> new SubjectResponse(subject.key(), subject.name())).toList(),
                areas.topicsNotAssessed().stream().map(subject -> new SubjectResponse(subject.key(), subject.name())).toList(),
                areas.recommendationCap());
    }

    private static @Nullable GapTopicResponse topic(KnowledgeGapReport.@Nullable GapTopic topic) {
        return topic == null ? null : new GapTopicResponse(topic.key(), topic.name());
    }

    private static AssessmentResponse assessment(InterviewReport report) {
        Coverage coverage = report.getCoverage();
        if (coverage == null) {
            return null;
        }
        return new AssessmentResponse(
                coverage.assessmentCoverage().doubleValue(),
                coverage.assessedWeight(),
                coverage.intendedWeight(),
                coverage.unassessable().stream()
                        .map(answer -> new UnassessableAnswerResponse(answer.questionId(), answer.sequenceNo(), answer.section(), answer.reason()))
                        .toList());
    }

    /** The evaluations a report cites: its narrative entries and its integrity notes. */
    static List<UUID> citedEvaluations(InterviewReport report) {
        List<UUID> ids = new ArrayList<>();
        ReportNarrativeDocument narrative = report.getNarrative();
        if (narrative != null) {
            narrative.strengths().forEach(entry -> ids.addAll(entry.evaluationIds()));
            narrative.improvementAreas().forEach(entry -> ids.addAll(entry.evaluationIds()));
        }
        List<IntegrityNote> notes = report.getIntegrityNotes();
        if (notes != null) {
            notes.forEach(note -> ids.add(note.evaluationId()));
        }
        return ids;
    }

    /** The first characters of an answer, whitespace collapsed, for the evidence of a statement. */
    static String excerpt(String answerText) {
        String text = answerText.strip().replaceAll("\\s+", " ");
        return text.length() <= MAX_EXCERPT_LENGTH ? text : text.substring(0, MAX_EXCERPT_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
    }

    private static List<SectionScoreResponse> sections(InterviewReport report) {
        List<SectionScore> scores = report.getSectionScores();
        return scores == null ? List.of() : scores.stream().map(score -> new SectionScoreResponse(score.section(), score.score())).toList();
    }

    private static List<SkillScoreResponse> skills(List<ReportSkillScore> rows, Map<Long, SkillRef> skillRefs) {
        List<SkillScoreResponse> skills = new ArrayList<>();
        for (ReportSkillScore row : rows) {
            SkillRef ref = skillRefs.get(row.getSkillId());
            if (ref == null) {
                throw new IllegalStateException("skill " + row.getSkillId() + " of report " + row.getReportId() + " is missing from the catalog");
            }
            skills.add(new SkillScoreResponse(
                    ref.name(),
                    ref.slug(),
                    row.getScore().doubleValue(),
                    row.getConfidence(),
                    row.getEvidenceCount(),
                    row.getAvgDifficulty().doubleValue()));
        }
        return skills;
    }

    private static List<NarrativeItemResponse> items(List<NarrativeEntry> entries, Map<UUID, EvidenceResponse> evidence) {
        return entries.stream()
                .map(entry -> new NarrativeItemResponse(
                        entry.text(),
                        entry.evaluationIds().stream().map(evidence::get).filter(Objects::nonNull).distinct().toList()))
                .toList();
    }

    private static List<IntegrityNoteResponse> integrityNotes(InterviewReport report, Map<UUID, EvidenceResponse> evidence) {
        List<IntegrityNote> notes = report.getIntegrityNotes();
        if (notes == null) {
            return List.of();
        }
        List<IntegrityNoteResponse> responses = new ArrayList<>();
        for (IntegrityNote note : notes) {
            EvidenceResponse cited = evidence.get(note.evaluationId());
            if (cited != null) {
                responses.add(new IntegrityNoteResponse(note.flag(), cited.questionId(), note.note()));
            }
        }
        return responses;
    }
}
