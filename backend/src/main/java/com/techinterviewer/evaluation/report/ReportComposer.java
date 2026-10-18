package com.techinterviewer.evaluation.report;

import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.evaluation.KnowledgeGapAnalyzer;
import com.techinterviewer.evaluation.RecommendationPolicy;
import com.techinterviewer.evaluation.ReportAggregator;
import com.techinterviewer.evaluation.ReportAnswer;
import com.techinterviewer.evaluation.ReportScores;
import com.techinterviewer.evaluation.domain.AssessmentAreas;
import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.IntegrityNote;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.SectionScore;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.port.EvaluationDigest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Computes the deterministic part of a report from the evaluated answers: {@link ReportAggregator} for the scores,
 * {@link RecommendationPolicy} for the band (capped when the fundamentals are weak), {@link KnowledgeGapAnalyzer} for the knowledge
 * gaps, and the mapping of their results to what is stored (section scores, integrity notes per evaluation, skill rows with their
 * database ids, coverage with the minutes used, the assessment areas with what the interview meant to assess and did not).
 */
final class ReportComposer {

    private static final String ELLIPSIS = "...";

    private ReportComposer() {
    }

    /**
     * @param entries     every answered or skipped question of the interview; none may await its evaluation
     * @param minutesUsed minutes between the start and the end of the interview
     * @param minCoverage share of the intended assessment weight that must be assessable when some answers are unassessable
     *                    (0 to 1); exactly this share is enough
     * @throws IllegalArgumentException when an entry awaits its evaluation, or the entries contradict each other
     */
    static ComposedReport compose(List<ReportEntry> entries, int minutesUsed, BigDecimal minCoverage) {
        return compose(entries, minutesUsed, minCoverage, ReportScope.NONE);
    }

    /**
     * As {@link #compose(List, int, BigDecimal)}, with what the interview meant to assess, so the report can say what it did not.
     */
    static ComposedReport compose(List<ReportEntry> entries, int minutesUsed, BigDecimal minCoverage, ReportScope scope) {
        List<ReportAnswer> answers = new ArrayList<>();
        Map<UUID, ReportEntry> byQuestion = new HashMap<>();
        Map<String, TopicInfo> skills = new HashMap<>();
        Map<String, KnowledgeGapReport.GapTopic> topics = new HashMap<>();
        List<KnowledgeGapAnalyzer.AnswerFindings> findings = new ArrayList<>();
        for (ReportEntry entry : entries) {
            if (entry.awaitsEvaluation()) {
                throw new IllegalArgumentException("question " + entry.questionId() + " is answered but not evaluated yet");
            }
            byQuestion.put(entry.questionId(), entry);
            if (entry.topic() != null) {
                skills.putIfAbsent(entry.topic().skillSlug(), entry.topic());
                topics.putIfAbsent(entry.topic().key(), new KnowledgeGapReport.GapTopic(entry.topic().key(), ReportEntry.topicName(entry.topic())));
            }
            entry.findings().ifPresent(findings::add);
            answers.add(new ReportAnswer(
                    entry.questionId(),
                    entry.threadId(),
                    entry.sequenceNo(),
                    entry.kind(),
                    entry.section(),
                    entry.topic() == null ? null : entry.topic().key(),
                    entry.difficulty(),
                    entry.skipped(),
                    entry.score(),
                    entry.flags(),
                    entry.unassessable(),
                    entry.area(),
                    entry.communicationRating()));
        }
        ReportScores scores = ReportAggregator.aggregate(answers);
        ReportOutcome outcome = outcome(scores.assessment(), minCoverage);
        // An inconclusive report states no verdict: the numbers of the assessable part are in the sections and skills, with their
        // evidence counts, but a score and a band over too little of the interview would be false precision.
        boolean verdict = outcome != ReportOutcome.INCONCLUSIVE;
        RecommendationPolicy.Capped band = verdict ? RecommendationPolicy.recommendWithFundamentals(scores) : new RecommendationPolicy.Capped(null, null);
        Recommendation recommendation = band.recommendation();
        return new ComposedReport(
                outcome,
                verdict && scores.overallScore() != null ? BigDecimal.valueOf(scores.overallScore()) : null,
                recommendation,
                scores.sections().stream().map(section -> new SectionScore(section.section(), section.score())).toList(),
                skillRows(scores, skills),
                integrityNotes(scores, byQuestion),
                new Coverage(
                        scores.coverage().questionsAnswered(),
                        scores.coverage().skipped(),
                        scores.coverage().followUps(),
                        minutesUsed,
                        scores.assessment().assessedWeight(),
                        scores.assessment().intendedWeight(),
                        scores.assessment().unassessable()),
                digests(entries),
                KnowledgeGapAnalyzer.analyze(findings, scores.topics(), topics),
                assessmentAreas(scores, answers, scope, band.capReason()));
    }

    /**
     * The areas with whether the plan meant to assess them, the selected skills and planned topics without an assessed answer (a
     * skipped or unassessable answer assesses nothing), and the reason the band was capped.
     */
    static AssessmentAreas assessmentAreas(ReportScores scores, List<ReportAnswer> answers, ReportScope scope, @Nullable String capReason) {
        List<AssessmentAreas.AreaScore> areas = scores.areas().stream()
                .map(area -> new AssessmentAreas.AreaScore(area.area(), scope.plannedAreas().contains(area.area()), area.score(),
                        area.evidenceCount(), area.confidence(), area.shareOfOverall(), area.lowConfidenceAnswers()))
                .toList();
        Set<String> assessedTopics = new HashSet<>();
        Set<String> assessedSkills = new HashSet<>();
        for (ReportAnswer answer : answers) {
            if (answer.score() != null && answer.topic() != null) {
                assessedTopics.add(answer.topic());
                assessedSkills.add(answer.skill());
            }
        }
        return new AssessmentAreas(
                areas,
                scope.skills().stream().filter(skill -> !assessedSkills.contains(skill.key())).toList(),
                scope.plannedTopics().stream().filter(topic -> !assessedTopics.contains(topic.key())).toList(),
                capReason);
    }

    /**
     * COMPLETE when every answer was assessed. Otherwise PARTIAL when something could be assessed and at least {@code minCoverage}
     * of the intended weight was, INCONCLUSIVE when not (including when nothing at all could be assessed). The comparison is
     * exact: {@code minCoverage * intended <= assessed}, so a coverage exactly at the threshold is enough.
     */
    static ReportOutcome outcome(ReportScores.Assessment assessment, BigDecimal minCoverage) {
        if (assessment.unassessable().isEmpty()) {
            return ReportOutcome.COMPLETE;
        }
        boolean enough = assessment.assessedWeight() > 0
                && minCoverage.multiply(BigDecimal.valueOf(assessment.intendedWeight()))
                        .compareTo(BigDecimal.valueOf(assessment.assessedWeight())) <= 0;
        return enough ? ReportOutcome.PARTIAL : ReportOutcome.INCONCLUSIVE;
    }

    private static List<ComposedReport.SkillRow> skillRows(ReportScores scores, Map<String, TopicInfo> skills) {
        List<ComposedReport.SkillRow> rows = new ArrayList<>();
        for (ReportScores.SkillScore skill : scores.skills()) {
            TopicInfo info = skills.get(skill.skill());
            if (info == null) {
                throw new IllegalStateException("skill " + skill.skill() + " has no catalog entry");
            }
            rows.add(new ComposedReport.SkillRow(
                    info.skillId(),
                    info.skillSlug(),
                    info.skillName(),
                    BigDecimal.valueOf(skill.score()),
                    skill.confidence(),
                    skill.evidenceCount(),
                    BigDecimal.valueOf(skill.averageDifficulty())));
        }
        return rows;
    }

    /** One note per evaluation that carries a flag: the aggregate lists question ids, the stored note cites the evaluation. */
    private static List<IntegrityNote> integrityNotes(ReportScores scores, Map<UUID, ReportEntry> byQuestion) {
        List<IntegrityNote> notes = new ArrayList<>();
        for (ReportScores.IntegrityNote note : scores.integrityNotes()) {
            for (UUID questionId : note.questionIds()) {
                notes.add(new IntegrityNote(note.flag(), byQuestion.get(questionId).evaluationId(), note.message()));
            }
        }
        return notes;
    }

    private static List<EvaluationDigest> digests(List<ReportEntry> entries) {
        return entries.stream()
                .filter(entry -> entry.evaluationId() != null)
                .sorted(Comparator.comparingInt(ReportEntry::sequenceNo))
                .map(entry -> new EvaluationDigest(
                        entry.evaluationId(),
                        entry.section(),
                        entry.topic() == null ? null : entry.topic().key(),
                        entry.difficulty(),
                        entry.score(),
                        shorten(entry.summary()),
                        entry.flags(),
                        entry.gaps()))
                .toList();
    }

    private static String shorten(String summary) {
        int max = AnswerSummary.MAX_LENGTH;
        return summary.length() <= max ? summary : summary.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }
}
