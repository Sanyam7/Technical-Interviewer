package com.techinterviewer.interview.port;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.FollowUpMode;
import org.jspecify.annotations.Nullable;

/**
 * What a follow-up question must aim at, decided by the follow-up policy: the question being followed up, what
 * the candidate answered, and the gap or claim to probe. The key point is the server's own; the generator must
 * word a question that does not reveal its content.
 *
 * @param mode                why the follow-up is asked
 * @param parentQuestionPrompt the question being followed up
 * @param candidateAnswer     the candidate's answer to it
 * @param targetKeyPoint      PROBE_GAP only: the missed key point
 * @param targetClaim         PROBE_CLAIM only: the claim to probe
 * @param designProbe         PROBE_DIMENSION only: the design dimension, its scenario and the design so far
 */
public record FollowUpTarget(
        FollowUpMode mode,
        String parentQuestionPrompt,
        String candidateAnswer,
        @Nullable ExpectedKeyPoint targetKeyPoint,
        @Nullable String targetClaim,
        @Nullable DesignProbe designProbe) {

    public static final int MAX_ANSWER_LENGTH = 20_000;

    public FollowUpTarget {
        Require.nonNull(mode, "mode");
        Require.notBlank(parentQuestionPrompt, "parentQuestionPrompt", 4000);
        Require.text(candidateAnswer, "candidateAnswer", MAX_ANSWER_LENGTH);
        targetClaim = Require.nullOrNotBlank(targetClaim, "targetClaim", 300);
        if ((mode == FollowUpMode.PROBE_GAP) != (targetKeyPoint != null)) {
            throw new IllegalArgumentException("targetKeyPoint is required for PROBE_GAP and not allowed otherwise");
        }
        if ((mode == FollowUpMode.PROBE_CLAIM) != (targetClaim != null)) {
            throw new IllegalArgumentException("targetClaim is required for PROBE_CLAIM and not allowed otherwise");
        }
        if ((mode == FollowUpMode.PROBE_DIMENSION) != (designProbe != null)) {
            throw new IllegalArgumentException("designProbe is required for PROBE_DIMENSION and not allowed otherwise");
        }
    }

    /** A target that is not a design probe. */
    public FollowUpTarget(FollowUpMode mode, String parentQuestionPrompt, String candidateAnswer, @Nullable ExpectedKeyPoint targetKeyPoint,
            @Nullable String targetClaim) {
        this(mode, parentQuestionPrompt, candidateAnswer, targetKeyPoint, targetClaim, null);
    }

    /** A probe of a design dimension. */
    public static FollowUpTarget designProbe(String parentQuestionPrompt, String candidateAnswer, DesignProbe probe) {
        return new FollowUpTarget(FollowUpMode.PROBE_DIMENSION, parentQuestionPrompt, candidateAnswer, null, null,
                Require.nonNull(probe, "probe"));
    }
}
