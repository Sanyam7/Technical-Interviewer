package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.FollowUpMode;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of the follow-up policy: probe a gap, probe a claim, deepen, or move on (no follow-up), and the rule
 * that decided it.
 *
 * @param mode             why to follow up; null for MOVE_ON
 * @param targetKeyPointId PROBE_GAP only: the key point to probe
 * @param targetClaim      PROBE_CLAIM only: the claim to probe
 * @param targetCriterion  PROBE_DIMENSION only: the design dimension to probe
 * @param reason           the rule of {@link FollowUpPolicy} or {@link DesignProbePolicy} that decided
 */
public record FollowUpDecision(
        @Nullable FollowUpMode mode, @Nullable String targetKeyPointId, @Nullable String targetClaim, @Nullable Criterion targetCriterion,
        Reason reason) {

    /** The rules of the policy, in the order it applies them. */
    public enum Reason {
        NO_SCORE("the answer has no score (evaluation pending or skipped)"),
        REPEATED_ANSWER("the answer repeats an earlier answer: probing it would get nothing new"),
        DEPTH_LIMIT("the thread has reached the follow-up depth"),
        SECTION_TIME("the section has less than 3 minutes left"),
        BREADTH_RESERVE("the time left is reserved for skills not asked yet"),
        TOO_WEAK("the score is below 35: the topic is not ground further"),
        KEY_POINT_GAP("the score is between 35 and 80 and a key point is missing or partial"),
        CLOSING("the interview is in its closing minutes: only gaps are probed"),
        UNPROBED_CLAIM("the answer made a claim that was not probed yet"),
        EXCELLENT("the score is 85 or more, a harder level exists and there is time"),
        DESIGN_SHORTFALL("of the dimensions not probed yet, the design falls furthest short on this one, by weight"),
        NOTHING_TO_PROBE("no rule calls for a follow-up");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public FollowUpDecision {
        Require.nonNull(reason, "reason");
        if ((mode == FollowUpMode.PROBE_GAP) != (targetKeyPointId != null)) {
            throw new IllegalArgumentException("targetKeyPointId is required for PROBE_GAP and not allowed otherwise");
        }
        if ((mode == FollowUpMode.PROBE_CLAIM) != (targetClaim != null)) {
            throw new IllegalArgumentException("targetClaim is required for PROBE_CLAIM and not allowed otherwise");
        }
        if ((mode == FollowUpMode.PROBE_DIMENSION) != (targetCriterion != null)) {
            throw new IllegalArgumentException("targetCriterion is required for PROBE_DIMENSION and not allowed otherwise");
        }
        Reason expected = switch (mode) {
            case PROBE_GAP -> Reason.KEY_POINT_GAP;
            case PROBE_CLAIM -> Reason.UNPROBED_CLAIM;
            case DEEPEN -> Reason.EXCELLENT;
            case PROBE_DIMENSION -> Reason.DESIGN_SHORTFALL;
            case null -> null;
        };
        if (expected != null ? reason != expected
                : reason == Reason.KEY_POINT_GAP || reason == Reason.UNPROBED_CLAIM || reason == Reason.EXCELLENT
                        || reason == Reason.DESIGN_SHORTFALL) {
            throw new IllegalArgumentException("reason " + reason + " does not fit mode " + mode);
        }
    }

    /** A decision that probes no design dimension. */
    public FollowUpDecision(@Nullable FollowUpMode mode, @Nullable String targetKeyPointId, @Nullable String targetClaim, Reason reason) {
        this(mode, targetKeyPointId, targetClaim, null, reason);
    }

    public static FollowUpDecision moveOn(Reason reason) {
        return new FollowUpDecision(null, null, null, reason);
    }

    public static FollowUpDecision probeGap(String keyPointId) {
        return new FollowUpDecision(FollowUpMode.PROBE_GAP, Require.notBlank(keyPointId, "keyPointId"), null, Reason.KEY_POINT_GAP);
    }

    public static FollowUpDecision probeClaim(String claim) {
        return new FollowUpDecision(FollowUpMode.PROBE_CLAIM, null, Require.notBlank(claim, "claim"), Reason.UNPROBED_CLAIM);
    }

    public static FollowUpDecision deepen() {
        return new FollowUpDecision(FollowUpMode.DEEPEN, null, null, Reason.EXCELLENT);
    }

    public static FollowUpDecision probeDimension(Criterion criterion) {
        return new FollowUpDecision(FollowUpMode.PROBE_DIMENSION, null, null, Require.nonNull(criterion, "criterion"), Reason.DESIGN_SHORTFALL);
    }

    /** True for PROBE_GAP, PROBE_CLAIM, DEEPEN and PROBE_DIMENSION. */
    public boolean asksFollowUp() {
        return mode != null;
    }
}
