package com.techinterviewer.evaluation.service;

import com.techinterviewer.common.validation.Require;

/**
 * What {@link EvaluationService#evaluatePending} achieved.
 *
 * @param evaluated    answers whose evaluation this call stored
 * @param stillPending answers of the interview that still wait for an attempt afterwards (an attempt is in progress or backing
 *                     off for longer than a caller waits); they are neither evaluated nor given up
 * @param unassessable answers of the interview that are UNASSESSABLE afterwards, whenever they were given up
 */
public record PendingEvaluationResult(int evaluated, int stillPending, int unassessable) {

    public PendingEvaluationResult {
        Require.atLeast(evaluated, 0, "evaluated");
        Require.atLeast(stillPending, 0, "stillPending");
        Require.atLeast(unassessable, 0, "unassessable");
    }

    public PendingEvaluationResult(int evaluated, int stillPending) {
        this(evaluated, stillPending, 0);
    }

    /** True when no answer waits for an attempt: every answer is evaluated, skipped or given up. */
    public boolean complete() {
        return stillPending == 0;
    }
}
