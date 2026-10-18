package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.engine.FollowUpDecision.Reason;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which design dimension the interviewer asks about next (AI_ARCHITECTURE.md section 2.12). */
class DesignProbePolicyTest {

    private static final Duration PLENTY = Duration.ofMinutes(20);

    /** The design rubric with every dimension at {@code rating} except those given. */
    private static List<CriterionScore> design(int rating, Object... criteriaAndRatings) {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        for (int i = 0; i < criteriaAndRatings.length; i += 2) {
            ratings.put((Criterion) criteriaAndRatings[i], (Integer) criteriaAndRatings[i + 1]);
        }
        return RubricRegistry.forEvaluator(Evaluator.SYSTEM_DESIGN).weights().stream()
                .map(weight -> new CriterionScore(weight.criterion(), ratings.getOrDefault(weight.criterion(), rating),
                        weight.weight().doubleValue(), ""))
                .toList();
    }

    private static DesignProbePolicy.Choice decide(List<CriterionScore> criteria, Set<Criterion> probed, int probesSoFar, Duration left) {
        return DesignProbePolicy.decide(new DesignProbePolicy.Input(60.0, criteria, probed, probesSoFar, 4, left, false));
    }

    @Test
    void theLargestWeightedShortfallIsProbedAndItsSizeIsReported() {
        DesignProbePolicy.Choice choice = decide(design(3, Criterion.OBSERVABILITY, 0, Criterion.FAILURE_HANDLING, 1), Set.of(), 0, PLENTY);

        // OBSERVABILITY 0 loses 0.04 x 4 = 0.16; FAILURE_HANDLING 1 loses 0.07 x 3 = 0.21.
        assertThat(choice.decision()).isEqualTo(FollowUpDecision.probeDimension(Criterion.FAILURE_HANDLING));
        assertThat(choice.shortfall()).isEqualByComparingTo("0.21");
        assertThat(choice.target().criterion()).isEqualTo(Criterion.FAILURE_HANDLING);
    }

    @Test
    void onEqualShortfallsTheFirstDimensionInRubricOrderIsProbed() {
        // HIGH_LEVEL_DESIGN 3 (0.12 x 1) and API_DESIGN 2 (0.06 x 2) both lose 0.12, computed exactly.
        DesignProbePolicy.Choice choice = decide(design(4, Criterion.HIGH_LEVEL_DESIGN, 3, Criterion.API_DESIGN, 2), Set.of(), 0, PLENTY);

        assertThat(choice.decision().targetCriterion()).isEqualTo(Criterion.HIGH_LEVEL_DESIGN);
    }

    @Test
    void probedDimensionsAreSkippedAndAFullyRatedDesignEndsTheDiscussion() {
        List<CriterionScore> criteria = design(4, Criterion.CACHING, 1, Criterion.SECURITY, 2);

        assertThat(decide(criteria, Set.of(Criterion.CACHING), 1, PLENTY).decision().targetCriterion()).isEqualTo(Criterion.SECURITY);
        assertThat(decide(criteria, Set.of(Criterion.CACHING, Criterion.SECURITY), 2, PLENTY).decision())
                .isEqualTo(FollowUpDecision.moveOn(Reason.NOTHING_TO_PROBE));
        assertThat(decide(design(4), Set.of(), 0, PLENTY).decision().asksFollowUp()).isFalse();
    }

    @Test
    void noScoreARepeatedAnswerTheProbeLimitOrTooLittleTimeEndTheDiscussionInThatOrder() {
        List<CriterionScore> weak = design(1);
        assertThat(DesignProbePolicy.decide(new DesignProbePolicy.Input(null, List.of(), Set.of(), 0, 4, PLENTY, true)).decision().reason())
                .isEqualTo(Reason.NO_SCORE);
        assertThat(DesignProbePolicy.decide(new DesignProbePolicy.Input(30.0, weak, Set.of(), 4, 4, Duration.ZERO, true)).decision().reason())
                .isEqualTo(Reason.REPEATED_ANSWER);
        assertThat(decide(weak, Set.of(), 4, Duration.ZERO).decision().reason()).isEqualTo(Reason.DEPTH_LIMIT);
        assertThat(decide(weak, Set.of(), 1, Duration.ofSeconds(179)).decision().reason()).isEqualTo(Reason.SECTION_TIME);
        assertThat(decide(weak, Set.of(), 1, Duration.ofMinutes(3)).decision().mode()).isEqualTo(FollowUpMode.PROBE_DIMENSION);
    }

    @Test
    void theScoreDoesNotGateAProbe() {
        DesignProbePolicy.Choice choice = DesignProbePolicy.decide(new DesignProbePolicy.Input(5.0, design(0), Set.of(), 0, 4, PLENTY, false));

        assertThat(choice.decision().targetCriterion()).as("architecture and scalability weigh most; architecture comes first")
                .isEqualTo(Criterion.HIGH_LEVEL_DESIGN);
    }

    @Test
    void inputsAreRangeChecked() {
        assertThatThrownBy(() -> new DesignProbePolicy.Input(50.0, List.of(), Set.of(), 7, 4, PLENTY, false)).hasMessageContaining("probesSoFar");
        assertThatThrownBy(() -> new DesignProbePolicy.Input(50.0, List.of(), Set.of(), 0, 7, PLENTY, false)).hasMessageContaining("maxProbes");
    }
}
