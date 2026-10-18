package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.engine.FollowUpDecision.Reason;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Decides the follow-ups of a system design problem (AI_ARCHITECTURE.md section 2.12). A design is discussed, not answered
 * once: after every answer of the thread the evaluator has rated the cumulative design on each dimension of the rubric, and
 * the interviewer asks next about the dimension where the design loses the most points. The rules, the first that applies
 * wins:
 *
 * <ol>
 *   <li>no score (evaluation pending or the probe skipped): MOVE_ON, there is nothing to choose from;
 *   <li>the answer repeats an earlier answer: MOVE_ON;
 *   <li>the thread has had {@code maxProbes} probes: MOVE_ON;
 *   <li>less than 3 minutes left in the section: MOVE_ON;
 *   <li>among the dimensions not probed yet, the one with the largest shortfall {@code weight × (4 − rating)}: PROBE_DIMENSION
 *       (the first in rubric order on a tie); when every such dimension is rated 4, MOVE_ON.
 * </ol>
 *
 * The score of the answer does not gate a probe, unlike {@link FollowUpPolicy}: a weak first design still gets asked about the
 * dimensions it missed, because the discussion is how a design interview gives a candidate the chance to show them. The model
 * never chooses the dimension; it only words the question about it.
 */
public final class DesignProbePolicy {

    public static final Duration MIN_TIME_FOR_PROBE = FollowUpPolicy.MIN_TIME_FOR_FOLLOW_UP;

    private static final BigDecimal MAX = BigDecimal.valueOf(CriterionScore.MAX_RATING);

    /**
     * What the policy looks at.
     *
     * @param overallScore      score of the answer; null when there is none
     * @param criteria          the scored ratings and weights of the cumulative design, in rubric order
     * @param probed            the dimensions this thread has probed already
     * @param probesSoFar       the probes asked in this thread (the follow-up depth of the answered question)
     * @param maxProbes         the probes the plan allows after a design problem
     * @param timeLeftInSection time the design section has left
     * @param repeatedAnswer    the answer repeats an earlier answer of the interview
     */
    public record Input(
            @Nullable Double overallScore,
            List<CriterionScore> criteria,
            Set<Criterion> probed,
            int probesSoFar,
            int maxProbes,
            Duration timeLeftInSection,
            boolean repeatedAnswer) {

        public Input {
            criteria = Immutables.list(criteria);
            probed = Immutables.enumSet(probed, Criterion.class);
            Require.between(probesSoFar, 0, InterviewPlan.MAX_DESIGN_PROBES, "probesSoFar");
            Require.between(maxProbes, 0, InterviewPlan.MAX_DESIGN_PROBES, "maxProbes");
            Require.nonNull(timeLeftInSection, "timeLeftInSection");
        }
    }

    /** The decision and, for a probe, the shortfall that chose it. */
    public record Choice(FollowUpDecision decision, @Nullable CriterionScore target, BigDecimal shortfall) {
    }

    private DesignProbePolicy() {
    }

    public static Choice decide(Input input) {
        if (input.overallScore() == null) {
            return moveOn(Reason.NO_SCORE);
        }
        if (input.repeatedAnswer()) {
            return moveOn(Reason.REPEATED_ANSWER);
        }
        if (input.probesSoFar() >= input.maxProbes()) {
            return moveOn(Reason.DEPTH_LIMIT);
        }
        if (input.timeLeftInSection().compareTo(MIN_TIME_FOR_PROBE) < 0) {
            return moveOn(Reason.SECTION_TIME);
        }
        CriterionScore best = null;
        BigDecimal largest = BigDecimal.ZERO;
        for (CriterionScore score : input.criteria()) {
            if (input.probed().contains(score.criterion())) {
                continue;
            }
            BigDecimal shortfall = shortfall(score);
            if (shortfall.compareTo(largest) > 0) {
                best = score;
                largest = shortfall;
            }
        }
        if (best == null) {
            return moveOn(Reason.NOTHING_TO_PROBE);
        }
        return new Choice(FollowUpDecision.probeDimension(best.criterion()), best, largest);
    }

    /** What the dimension still lacks, in weighted rating points: {@code weight × (4 − rating)}. */
    public static BigDecimal shortfall(CriterionScore score) {
        return BigDecimal.valueOf(score.weight()).multiply(MAX.subtract(BigDecimal.valueOf(score.rating())));
    }

    private static Choice moveOn(Reason reason) {
        return new Choice(FollowUpDecision.moveOn(reason), null, BigDecimal.ZERO);
    }
}
