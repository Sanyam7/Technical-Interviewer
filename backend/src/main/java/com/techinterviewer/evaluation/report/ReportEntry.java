package com.techinterviewer.evaluation.report;

import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.KnowledgeGapAnalyzer;
import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An answered or skipped question as the report sees it: the answer joined with its question, its evaluation and its
 * topic, as plain values so the report arithmetic needs no persistence.
 *
 * @param questionId   the question
 * @param threadId     the root question of the thread; the question's own id for a root question
 * @param sequenceNo   position of the question in the interview
 * @param kind         kind of the question
 * @param section      section of the question (a follow-up has the section of its thread)
 * @param topic        the catalog topic when the question has one that counts as skill evidence (questioning section), else null
 * @param difficulty   level the question was asked at, 1 to 5
 * @param skipped      the candidate skipped it
 * @param evaluationId the completed evaluation of the answer; null for a skipped answer and for one still waiting
 * @param score        overall score 0 to 100 of that evaluation; null exactly when there is no evaluation
 * @param summary      the evaluator's one-line summary; empty without an evaluation
 * @param flags        flags of the evaluation; empty without one
 * @param unassessable why the answer could not be assessed (given up after its attempts, or refused); null otherwise. Such an
 *                     entry has no evaluation and is not waiting for one
 * @param gaps         the knowledge gaps of the evaluation as short texts, for the narrator
 * @param conceptGaps  the expected key points the evaluation judged MISSING or PARTIAL, for the knowledge-gap analysis
 * @param mistakes     the factual errors of the evaluation, for the knowledge-gap analysis
 * @param questionType the question's type (AI_ARCHITECTURE.md section 2.15); null outside the questioning section and before types existed
 * @param resumeAnchored the question was anchored on a résumé experience
 * @param communicationRating the evaluation's scored COMMUNICATION rating, 0 to 4; null when it has none
 */
record ReportEntry(
        UUID questionId,
        UUID threadId,
        int sequenceNo,
        QuestionKind kind,
        Section section,
        @Nullable TopicInfo topic,
        int difficulty,
        boolean skipped,
        @Nullable UUID evaluationId,
        @Nullable Double score,
        String summary,
        Set<EvaluationFlag> flags,
        @Nullable EvaluationFailure unassessable,
        List<String> gaps,
        List<KnowledgeGapAnalyzer.ConceptGap> conceptGaps,
        List<FactualError> mistakes,
        @Nullable QuestionType questionType,
        boolean resumeAnchored,
        @Nullable Integer communicationRating) {

    ReportEntry {
        Require.nonNull(questionId, "questionId");
        Require.nonNull(threadId, "threadId");
        Require.nonNull(kind, "kind");
        Require.nonNull(section, "section");
        Require.nonNull(summary, "summary");
        flags = Set.copyOf(Require.nonNull(flags, "flags"));
        gaps = List.copyOf(Require.nonNull(gaps, "gaps"));
        conceptGaps = List.copyOf(Require.nonNull(conceptGaps, "conceptGaps"));
        mistakes = List.copyOf(Require.nonNull(mistakes, "mistakes"));
        if (evaluationId == null && (!conceptGaps.isEmpty() || !mistakes.isEmpty())) {
            throw new IllegalArgumentException("only an evaluated answer has findings");
        }
        if ((evaluationId == null) != (score == null)) {
            throw new IllegalArgumentException("an entry has an evaluation id and a score, or neither");
        }
        if (skipped && evaluationId != null) {
            throw new IllegalArgumentException("a skipped answer has no evaluation");
        }
        if (unassessable != null && (skipped || evaluationId != null)) {
            throw new IllegalArgumentException("an unassessable answer is neither skipped nor evaluated");
        }
        if (communicationRating != null && evaluationId == null) {
            throw new IllegalArgumentException("only an evaluated answer has a communication rating");
        }
    }

    /** An entry without a question type or communication rating. */
    ReportEntry(
            UUID questionId,
            UUID threadId,
            int sequenceNo,
            QuestionKind kind,
            Section section,
            @Nullable TopicInfo topic,
            int difficulty,
            boolean skipped,
            @Nullable UUID evaluationId,
            @Nullable Double score,
            String summary,
            Set<EvaluationFlag> flags,
            @Nullable EvaluationFailure unassessable,
            List<String> gaps,
            List<KnowledgeGapAnalyzer.ConceptGap> conceptGaps,
            List<FactualError> mistakes) {
        this(questionId, threadId, sequenceNo, kind, section, topic, difficulty, skipped, evaluationId, score, summary, flags, unassessable, gaps,
                conceptGaps, mistakes, null, false, null);
    }

    /** The assessment area the answer counts in; null for the introduction. */
    @Nullable AssessmentArea area() {
        return AssessmentArea.of(section, questionType, resumeAnchored);
    }

    /** An entry without structured findings: only the narrator's gap texts. */
    ReportEntry(
            UUID questionId,
            UUID threadId,
            int sequenceNo,
            QuestionKind kind,
            Section section,
            @Nullable TopicInfo topic,
            int difficulty,
            boolean skipped,
            @Nullable UUID evaluationId,
            @Nullable Double score,
            String summary,
            Set<EvaluationFlag> flags,
            @Nullable EvaluationFailure unassessable,
            List<String> gaps) {
        this(questionId, threadId, sequenceNo, kind, section, topic, difficulty, skipped, evaluationId, score, summary, flags,
                unassessable, gaps, List.of(), List.of());
    }

    ReportEntry(
            UUID questionId,
            UUID threadId,
            int sequenceNo,
            QuestionKind kind,
            Section section,
            @Nullable TopicInfo topic,
            int difficulty,
            boolean skipped,
            @Nullable UUID evaluationId,
            @Nullable Double score,
            String summary,
            Set<EvaluationFlag> flags) {
        this(questionId, threadId, sequenceNo, kind, section, topic, difficulty, skipped, evaluationId, score, summary, flags, null);
    }

    /** An entry without detected knowledge gaps. */
    ReportEntry(
            UUID questionId,
            UUID threadId,
            int sequenceNo,
            QuestionKind kind,
            Section section,
            @Nullable TopicInfo topic,
            int difficulty,
            boolean skipped,
            @Nullable UUID evaluationId,
            @Nullable Double score,
            String summary,
            Set<EvaluationFlag> flags,
            @Nullable EvaluationFailure unassessable) {
        this(questionId, threadId, sequenceNo, kind, section, topic, difficulty, skipped, evaluationId, score, summary, flags,
                unassessable, List.of());
    }

    /** What the knowledge-gap analysis reads from this entry; empty without an evaluation. */
    Optional<KnowledgeGapAnalyzer.AnswerFindings> findings() {
        if (evaluationId == null) {
            return Optional.empty();
        }
        KnowledgeGapReport.GapTopic gapTopic = topic == null ? null : new KnowledgeGapReport.GapTopic(topic.key(), topicName(topic));
        return Optional.of(new KnowledgeGapAnalyzer.AnswerFindings(questionId, threadId, sequenceNo, section, gapTopic, conceptGaps, mistakes));
    }

    static String topicName(TopicInfo topic) {
        return topic.skillName() + " / " + topic.name();
    }

    /** True when the answer was given and its evaluation is still missing and not given up: the report cannot be made yet. */
    boolean awaitsEvaluation() {
        return !skipped && unassessable == null && evaluationId == null;
    }
}
