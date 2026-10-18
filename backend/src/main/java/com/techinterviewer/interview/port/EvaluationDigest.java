package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the report narrator may know about one evaluated answer: the evaluation id it must cite, where it
 * happened, how it scored and the evaluator's one-line summary. There is no transcript.
 */
public record EvaluationDigest(
        UUID evaluationId,
        Section section,
        @Nullable String topic,
        int difficulty,
        double score,
        String summary,
        Set<EvaluationFlag> flags,
        List<String> gaps) {

    public static final int MAX_GAPS = 5;
    public static final int MAX_GAP_LENGTH = 300;

    public EvaluationDigest {
        Require.nonNull(evaluationId, "evaluationId");
        Require.nonNull(section, "section");
        topic = Require.nullOrNotBlank(topic, "topic", 160);
        Require.between(difficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "difficulty");
        Require.between(score, 0, 100, "score");
        Require.notBlank(summary, "summary", AnswerSummary.MAX_LENGTH);
        flags = Immutables.enumSet(flags, EvaluationFlag.class);
        gaps = Immutables.list(gaps);
        Require.between(gaps.size(), 0, MAX_GAPS, "gaps size");
        gaps.forEach(gap -> Require.notBlank(gap, "gaps entry", MAX_GAP_LENGTH));
    }

    /** A digest without detected knowledge gaps. */
    public EvaluationDigest(
            UUID evaluationId, Section section, @Nullable String topic, int difficulty, double score, String summary, Set<EvaluationFlag> flags) {
        this(evaluationId, section, topic, difficulty, score, summary, flags, List.of());
    }
}
