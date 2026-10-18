package com.techinterviewer.evaluation.domain;

/**
 * How much a judgement can be trusted, from LOW to HIGH. Used for two different things: how much evidence backs a
 * report score (counted by the server), and how sure the evaluator says it is of one evaluation (a self-report of the
 * model, not statistically calibrated). The constants equal the {@code ck_report_skill_scores_confidence} and
 * {@code ck_evaluations_evaluator_confidence} checks.
 */
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH;

    /** The lower of this level and {@code cap}. */
    public Confidence atMost(Confidence cap) {
        return compareTo(cap) <= 0 ? this : cap;
    }
}
