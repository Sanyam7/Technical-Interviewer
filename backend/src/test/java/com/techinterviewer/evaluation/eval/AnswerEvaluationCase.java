package com.techinterviewer.evaluation.eval;

import com.techinterviewer.TestJson;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.port.TopicRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One labelled case of {@code ai-evals/datasets/answer-evaluation.jsonl}: a question with its expected key points, an
 * answer of a known quality, and what a correct evaluation of it looks like. The labels were written by the dataset's
 * author (one rater), not by an independent panel; see ai-evals/ANSWER_EVALUATION.md.
 *
 * @param id         stable name
 * @param group      the question the case belongs to; every group has one answer of each category
 * @param category   the quality of the answer
 * @param evaluator  the rubric that judges it
 * @param difficulty level of the question, 1 to 5
 * @param keyPoints  what a good answer contains, by id
 * @param answer     the candidate's answer
 * @param thread     for a follow-up, the earlier turns of its thread as the evaluator receives them; null for a root question
 * @param expected   the expected behaviour of the evaluation
 */
public record AnswerEvaluationCase(
        String id,
        String group,
        Category category,
        Evaluator evaluator,
        String skill,
        String skillName,
        String topic,
        String topicName,
        int difficulty,
        String question,
        List<KeyPoint> keyPoints,
        String answer,
        @Nullable List<Turn> thread,
        Expected expected) {

    /**
     * The quality of an answer. INJECTION is the group's AVERAGE answer with an attempt to instruct the evaluator appended.
     * REPEATED is the group's GOOD answer pasted again, word for word, as the answer to a follow-up question it does not answer.
     */
    public enum Category {
        EXCELLENT,
        GOOD,
        AVERAGE,
        PARTIAL,
        INCORRECT,
        HALLUCINATED,
        INJECTION,
        REPEATED
    }

    /** An earlier question of the thread and the candidate's answer to it. */
    public record Turn(String question, String answer) {
    }

    public record KeyPoint(String id, String text) {
    }

    /**
     * What a correct evaluation does with the answer.
     *
     * @param humanScore     the score a careful interviewer would give, 0 to 100
     * @param scoreBand      the range the computed score must fall in, inclusive
     * @param keyPoints      for each constrained key point, the statuses that count as right (a key point not listed is not checked)
     * @param factualErrors  the inclusive range of factual errors the evaluation should list ([0, 0] for a correct answer)
     * @param mustFlag       flags the evaluation must carry
     * @param mustNotFlag    flags it must not carry
     * @param maxCorrectness the highest acceptable CORRECTNESS after the server's rules (wrong or invented content); null: unchecked
     * @param invarianceOf   for an injection case, the case it was made from: its score must not rise above that case's
     * @param injectionStyle how the injection is phrased, for the report
     */
    public record Expected(
            int humanScore,
            List<Integer> scoreBand,
            Map<String, List<KeyPointStatus>> keyPoints,
            List<Integer> factualErrors,
            Set<EvaluationFlag> mustFlag,
            Set<EvaluationFlag> mustNotFlag,
            @Nullable Integer maxCorrectness,
            @Nullable String invarianceOf,
            @Nullable String injectionStyle) {

        public int minScore() {
            return scoreBand.get(0);
        }

        public int maxScore() {
            return scoreBand.get(1);
        }

        public int minErrors() {
            return factualErrors.get(0);
        }

        public int maxErrors() {
            return factualErrors.get(1);
        }
    }

    public TopicRef topicRef() {
        return new TopicRef(skill, skillName, topic, topicName);
    }

    public List<ExpectedKeyPoint> expectedKeyPoints() {
        return keyPoints.stream().map(point -> new ExpectedKeyPoint(point.id(), point.text(), List.of())).toList();
    }

    /** The dataset at {@code answer.eval.dataset} (set by the build), or at its path relative to the backend. */
    public static List<AnswerEvaluationCase> load() {
        Path path = Path.of(System.getProperty("answer.eval.dataset", "../ai-evals/datasets/answer-evaluation.jsonl"));
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> TestJson.applicationMapper().readValue(line, AnswerEvaluationCase.class))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), unreadable);
        }
    }
}
