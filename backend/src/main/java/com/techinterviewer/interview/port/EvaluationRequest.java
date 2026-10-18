package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.QuestionDetails;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One answer to be evaluated (AI_ARCHITECTURE.md sections 2.3, 2.6, 2.7). Skipped answers are never sent: they
 * score zero deterministically.
 *
 * @param interviewId       the interview
 * @param answerId          the answer (for logging and call records)
 * @param evaluator         which rubric applies
 * @param topic             topic of the question, if it has one
 * @param difficulty        difficulty of the question, 1 to 5 (calibrates the judgement)
 * @param questionPrompt    the question as asked
 * @param expectedKeyPoints what a good answer contains (empty for the introduction)
 * @param details           coding material, for the coding evaluator
 * @param answerText        the candidate's answer; untrusted text, non-blank, at most 20 000 characters
 * @param codingLanguage    language of a code answer (required for the CODING evaluator)
 * @param thread            earlier turns of the same follow-up thread, oldest first (cumulative design evaluation)
 * @param memorySummary     compact summary of the interview memory (candidate claims, earlier answers), possibly empty
 * @param focusAreas        what the skill profile says matters most for this question (AI_ARCHITECTURE.md 2.10); may be empty
 * @param earlierMistakes   claims of the misconceptions the interview recorded so far, oldest first, so the evaluator can say
 *                          that a new mistake repeats one of them; may be empty
 * @param codeTests         the test results of a coding solution (or why there are none); null for every other answer
 * @param design            the diagram and the probed dimension of a system design answer; null for every other answer
 */
public record EvaluationRequest(
        UUID interviewId,
        UUID answerId,
        Evaluator evaluator,
        @Nullable TopicRef topic,
        int difficulty,
        String questionPrompt,
        List<ExpectedKeyPoint> expectedKeyPoints,
        @Nullable QuestionDetails details,
        String answerText,
        @Nullable String codingLanguage,
        List<ThreadTurn> thread,
        String memorySummary,
        List<String> focusAreas,
        List<String> earlierMistakes,
        @Nullable CodeTestEvidence codeTests,
        @Nullable DesignContext design) {

    public static final int MAX_ANSWER_LENGTH = 20_000;
    public static final int MAX_MEMORY_SUMMARY_LENGTH = 8_000;
    public static final int MAX_FOCUS_AREAS = 8;
    public static final int MAX_EARLIER_MISTAKES = 10;

    public EvaluationRequest {
        Require.nonNull(interviewId, "interviewId");
        Require.nonNull(answerId, "answerId");
        Require.nonNull(evaluator, "evaluator");
        Require.between(difficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "difficulty");
        Require.notBlank(questionPrompt, "questionPrompt", 4000);
        expectedKeyPoints = Immutables.list(expectedKeyPoints);
        Require.notBlank(answerText, "answerText", MAX_ANSWER_LENGTH);
        codingLanguage = Require.nullOrNotBlank(codingLanguage, "codingLanguage", 20);
        thread = Immutables.list(thread);
        Require.between(thread.size(), 0, 10, "thread size");
        Require.text(memorySummary, "memorySummary", MAX_MEMORY_SUMMARY_LENGTH);
        if (evaluator == Evaluator.CODING && codingLanguage == null) {
            throw new IllegalArgumentException("codingLanguage is required for the CODING evaluator");
        }
        focusAreas = Immutables.list(focusAreas);
        Require.between(focusAreas.size(), 0, MAX_FOCUS_AREAS, "focusAreas size");
        focusAreas.forEach(area -> Require.notBlank(area, "focus area", 120));
        earlierMistakes = Immutables.list(earlierMistakes);
        Require.between(earlierMistakes.size(), 0, MAX_EARLIER_MISTAKES, "earlierMistakes size");
        earlierMistakes.forEach(mistake -> Require.notBlank(mistake, "earlier mistake", 1000));
        if (codeTests != null && evaluator != Evaluator.CODING) {
            throw new IllegalArgumentException("only a coding answer has test results");
        }
        if (design != null && evaluator != Evaluator.SYSTEM_DESIGN) {
            throw new IllegalArgumentException("only a system design answer has a design context");
        }
    }

    /** A request that is not a system design answer. */
    public EvaluationRequest(
            UUID interviewId,
            UUID answerId,
            Evaluator evaluator,
            @Nullable TopicRef topic,
            int difficulty,
            String questionPrompt,
            List<ExpectedKeyPoint> expectedKeyPoints,
            @Nullable QuestionDetails details,
            String answerText,
            @Nullable String codingLanguage,
            List<ThreadTurn> thread,
            String memorySummary,
            List<String> focusAreas,
            List<String> earlierMistakes,
            @Nullable CodeTestEvidence codeTests) {
        this(interviewId, answerId, evaluator, topic, difficulty, questionPrompt, expectedKeyPoints, details, answerText, codingLanguage,
                thread, memorySummary, focusAreas, earlierMistakes, codeTests, null);
    }

    /** A request without test results. */
    public EvaluationRequest(
            UUID interviewId,
            UUID answerId,
            Evaluator evaluator,
            @Nullable TopicRef topic,
            int difficulty,
            String questionPrompt,
            List<ExpectedKeyPoint> expectedKeyPoints,
            @Nullable QuestionDetails details,
            String answerText,
            @Nullable String codingLanguage,
            List<ThreadTurn> thread,
            String memorySummary,
            List<String> focusAreas,
            List<String> earlierMistakes) {
        this(interviewId, answerId, evaluator, topic, difficulty, questionPrompt, expectedKeyPoints, details, answerText, codingLanguage,
                thread, memorySummary, focusAreas, earlierMistakes, null, null);
    }

    /** A request without focus areas and without earlier mistakes. */
    public EvaluationRequest(
            UUID interviewId,
            UUID answerId,
            Evaluator evaluator,
            @Nullable TopicRef topic,
            int difficulty,
            String questionPrompt,
            List<ExpectedKeyPoint> expectedKeyPoints,
            @Nullable QuestionDetails details,
            String answerText,
            @Nullable String codingLanguage,
            List<ThreadTurn> thread,
            String memorySummary) {
        this(interviewId, answerId, evaluator, topic, difficulty, questionPrompt, expectedKeyPoints, details, answerText,
                codingLanguage, thread, memorySummary, List.of(), List.of(), null, null);
    }
}
