package com.techinterviewer.evaluation.service;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.port.EvaluationRequest;
import java.time.Duration;

/** What {@link EvaluationService#beginAttempt} found when asked to start an evaluation attempt for one answer. */
public sealed interface EvaluationAttempt {

    /**
     * The attempt is claimed: this caller, and nobody else, evaluates the answer now. It reports the outcome with
     * {@link EvaluationService#record} or {@link EvaluationService#recordFailure}; if it never does (a crashed process) the
     * claim expires and the attempt counts as failed.
     *
     * @param request the question to put to the evaluator
     * @param number  the attempt number, from 1
     */
    record Started(EvaluationRequest request, int number) implements EvaluationAttempt {

        public Started {
            Require.nonNull(request, "request");
            Require.atLeast(number, 1, "number");
        }
    }

    /** Nothing may start yet: the backoff after a failed attempt, or an attempt in progress, ends after {@code remaining}. */
    record Waiting(Duration remaining) implements EvaluationAttempt {

        public Waiting {
            Require.nonNull(remaining, "remaining");
        }
    }

    /** The answer needs no more attempts: it is skipped, evaluated (COMPLETED) or given up (UNASSESSABLE). */
    record Settled(EvaluationStatus status) implements EvaluationAttempt {

        public Settled {
            Require.nonNull(status, "status");
            if (status == EvaluationStatus.PENDING) {
                throw new IllegalArgumentException("a pending answer is not settled");
            }
        }
    }
}
