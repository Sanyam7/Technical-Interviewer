package com.techinterviewer.evaluation.eval;

import com.techinterviewer.TestJson;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One labelled case of {@code ai-evals/datasets/design-evaluation.jsonl}: a design answer to a problem of the bank, possibly the
 * answer to a probe after earlier turns, possibly with a diagram, and what a correct evaluation does with it (AI_ARCHITECTURE.md
 * section 2.12). The labels were written by one rater (the dataset's author), not by an independent panel.
 *
 * @param problem      the id of a problem of the design bank: its statement and key points are used
 * @param thread       earlier turns; a question "PROBLEM" stands for the problem's statement
 * @param question     the latest question; null when the answer is the answer to the problem itself
 * @param architecture the diagram as the evaluator reads it, or null
 * @param probe        the dimension the latest question probes, or null
 */
public record DesignEvaluationCase(
        String id,
        String problem,
        int difficulty,
        List<Turn> thread,
        @Nullable String question,
        String answer,
        @Nullable String architecture,
        @Nullable Criterion probe,
        Expected expected,
        String note) {

    public record Turn(String question, String answer) {
    }

    /**
     * @param scoreBand     the range the computed score must fall in, inclusive
     * @param minRatings    dimensions that must rate at least this after the server's rules
     * @param maxRatings    dimensions that must rate at most this after the server's rules
     * @param factualErrors the inclusive range of factual errors
     * @param mustFlag      flags the evaluation must carry
     * @param invarianceOf  for an injection, the case it was made from: its score must not rise above that case's
     */
    public record Expected(List<Integer> scoreBand, Map<Criterion, Integer> minRatings, Map<Criterion, Integer> maxRatings,
            List<Integer> factualErrors, Set<EvaluationFlag> mustFlag, @Nullable String invarianceOf) {
    }

    public static List<DesignEvaluationCase> load() {
        Path path = Path.of(System.getProperty("design.eval.dataset", "../ai-evals/datasets/design-evaluation.jsonl"));
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> TestJson.applicationMapper().readValue(line, DesignEvaluationCase.class))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), unreadable);
        }
    }
}
