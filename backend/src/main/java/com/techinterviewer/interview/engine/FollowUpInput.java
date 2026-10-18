package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.InterviewPlan;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What {@link FollowUpPolicy} looks at.
 *
 * @param overallScore        score of the answer, 0 to 100; null when there is none (evaluation pending, or an answer skipped)
 * @param followUpDepth       follow-up depth of the answered question: 0 for a root question
 * @param maxFollowUpDepth    follow-ups allowed below a root question
 * @param timeLeftInSection   time the section has left, never negative
 * @param keyPoints           the question's expected key points in the order of their weight (the most important first), with their status
 * @param newClaims           claims this answer contained that have not been probed yet
 * @param claimProbeAllowed   whether the interview may still probe a claim in the current section
 * @param deeperLevelAvailable whether the topic can be asked one step harder than the answered question
 * @param repeatedAnswer      the answer repeats an earlier answer of the interview
 * @param closing             the interview is in its closing window
 * @param reservedForBreadth  section time kept for the skills that have not been asked yet; a follow-up may not use it
 */
public record FollowUpInput(
        @Nullable Double overallScore,
        int followUpDepth,
        int maxFollowUpDepth,
        Duration timeLeftInSection,
        List<KeyPointResult> keyPoints,
        List<String> newClaims,
        boolean claimProbeAllowed,
        boolean deeperLevelAvailable,
        boolean repeatedAnswer,
        boolean closing,
        Duration reservedForBreadth) {

    public FollowUpInput {
        if (overallScore != null) {
            Require.between(overallScore, 0, 100, "overallScore");
        }
        Require.between(followUpDepth, 0, InterviewPlan.MAX_FOLLOW_UP_DEPTH, "followUpDepth");
        Require.between(maxFollowUpDepth, 0, InterviewPlan.MAX_FOLLOW_UP_DEPTH, "maxFollowUpDepth");
        Require.nonNull(timeLeftInSection, "timeLeftInSection");
        if (timeLeftInSection.isNegative()) {
            throw new IllegalArgumentException("timeLeftInSection must not be negative");
        }
        Require.nonNull(reservedForBreadth, "reservedForBreadth");
        if (reservedForBreadth.isNegative()) {
            throw new IllegalArgumentException("reservedForBreadth must not be negative");
        }
        keyPoints = Immutables.list(keyPoints);
        newClaims = Immutables.list(newClaims);
        newClaims.forEach(claim -> Require.notBlank(claim, "newClaims entry"));
    }

    /** The inputs of the original policy: no repeated answer, not closing, nothing reserved. */
    public FollowUpInput(@Nullable Double overallScore, int followUpDepth, int maxFollowUpDepth, Duration timeLeftInSection,
            List<KeyPointResult> keyPoints, List<String> newClaims, boolean claimProbeAllowed, boolean deeperLevelAvailable) {
        this(overallScore, followUpDepth, maxFollowUpDepth, timeLeftInSection, keyPoints, newClaims, claimProbeAllowed,
                deeperLevelAvailable, false, false, Duration.ZERO);
    }

    /** Section time a follow-up may use: what is left after the reserve for skills not asked yet. */
    public Duration spareTime() {
        Duration spare = timeLeftInSection.minus(reservedForBreadth);
        return spare.isNegative() ? Duration.ZERO : spare;
    }
}
