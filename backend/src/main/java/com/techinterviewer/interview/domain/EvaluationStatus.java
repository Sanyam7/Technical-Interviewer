package com.techinterviewer.interview.domain;

/**
 * Evaluation progress of an answer. The constants equal the {@code ck_answers_evaluation_status} check.
 * A skipped answer is stored as COMPLETED (there is nothing to evaluate).
 *
 * <ul>
 *   <li>PENDING: waiting for an evaluation, or for another attempt after a failed one;
 *   <li>COMPLETED: evaluated (or skipped);
 *   <li>UNASSESSABLE: given up after the retry limit, or refused by the provider. Final. It is not a bad answer and never
 *       scores zero: the report leaves the answer out and says so.
 * </ul>
 */
public enum EvaluationStatus {
    PENDING,
    COMPLETED,
    UNASSESSABLE
}
