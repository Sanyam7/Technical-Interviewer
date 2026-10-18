package com.techinterviewer.interview.engine;

import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.engine.FollowUpDecision.Reason;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether an answer is followed up, and how (AI_ARCHITECTURE.md sections 2.4 and 2.9). The rules are
 * evaluated in order and the first that applies wins:
 *
 * <ol>
 *   <li>no score (evaluation pending or the question skipped): MOVE_ON, there is nothing to base a follow-up on;
 *   <li>the answer repeats an earlier answer: MOVE_ON, a probe would get the same text back;
 *   <li>depth of the answered question {@code >= max depth}: MOVE_ON;
 *   <li>less than 3 minutes left in the section: MOVE_ON; less than 3 minutes once the time reserved for the skills
 *       not asked yet is set aside: MOVE_ON (breadth before depth, so no topic eats the session);
 *   <li>overall below 35: MOVE_ON (do not grind a candidate on a topic they do not know; the level goes down instead);
 *   <li>overall from 35 up to (not including) 80 and a key point MISSING or PARTIAL: PROBE_GAP on a missing key point
 *       (the most important one: key points are listed by weight; a PARTIAL one only when none is MISSING);
 *   <li>in the closing window of the interview: MOVE_ON (claim probes and deepening are worth less than the questions
 *       still to come);
 *   <li>an unprobed claim in the answer while a claim probe is allowed: PROBE_CLAIM on the first such claim;
 *   <li>overall of 85 or more with at least 4 spare minutes and a harder level available: DEEPEN;
 *   <li>otherwise MOVE_ON.
 * </ol>
 *
 * Deviations from the document, both documented decisions: the document calls the key point "highest-weight"
 * but key points carry no weight, so their order stands for it; and DEEPEN needs a harder level to exist (a topic
 * already at the top of its range or of the band cannot be asked "one step harder").
 */
public final class FollowUpPolicy {

    public static final Duration MIN_TIME_FOR_FOLLOW_UP = Duration.ofMinutes(3);
    /** Spare time for DEEPEN: about one more root question. */
    public static final Duration MIN_TIME_FOR_DEEPEN = Duration.ofMinutes(4);
    public static final double GAP_PROBE_FLOOR = 35;
    public static final double GAP_PROBE_CEILING = 80;
    public static final double DEEPEN_THRESHOLD = 85;

    private FollowUpPolicy() {
    }

    public static FollowUpDecision decide(FollowUpInput input) {
        Double score = input.overallScore();
        if (score == null) {
            return FollowUpDecision.moveOn(Reason.NO_SCORE);
        }
        if (input.repeatedAnswer()) {
            return FollowUpDecision.moveOn(Reason.REPEATED_ANSWER);
        }
        if (input.followUpDepth() >= input.maxFollowUpDepth()) {
            return FollowUpDecision.moveOn(Reason.DEPTH_LIMIT);
        }
        if (input.timeLeftInSection().compareTo(MIN_TIME_FOR_FOLLOW_UP) < 0) {
            return FollowUpDecision.moveOn(Reason.SECTION_TIME);
        }
        if (input.spareTime().compareTo(MIN_TIME_FOR_FOLLOW_UP) < 0) {
            return FollowUpDecision.moveOn(Reason.BREADTH_RESERVE);
        }
        if (score < GAP_PROBE_FLOOR) {
            return FollowUpDecision.moveOn(Reason.TOO_WEAK);
        }
        if (score < GAP_PROBE_CEILING) {
            String gap = firstWithStatus(input, KeyPointStatus.MISSING);
            if (gap == null) {
                gap = firstWithStatus(input, KeyPointStatus.PARTIAL);
            }
            if (gap != null) {
                return FollowUpDecision.probeGap(gap);
            }
        }
        if (input.closing()) {
            return FollowUpDecision.moveOn(Reason.CLOSING);
        }
        if (input.claimProbeAllowed() && !input.newClaims().isEmpty()) {
            return FollowUpDecision.probeClaim(input.newClaims().getFirst());
        }
        if (score >= DEEPEN_THRESHOLD
                && input.spareTime().compareTo(MIN_TIME_FOR_DEEPEN) >= 0
                && input.deeperLevelAvailable()) {
            return FollowUpDecision.deepen();
        }
        return FollowUpDecision.moveOn(Reason.NOTHING_TO_PROBE);
    }

    private static @Nullable String firstWithStatus(FollowUpInput input, KeyPointStatus status) {
        return input.keyPoints().stream()
                .filter(keyPoint -> keyPoint.status() == status)
                .map(KeyPointResult::keyPointId)
                .findFirst()
                .orElse(null);
    }
}
