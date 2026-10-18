package com.techinterviewer.evaluation.domain;

/**
 * How much of the intended assessment a READY report stands on. The constants equal the {@code ck_reports_outcome} check.
 */
public enum ReportOutcome {
    /** Every answer that needed an assessment was assessed. */
    COMPLETE,
    /** Some answers could not be assessed, but enough of the assessment weight could: the scores cover the assessable part. */
    PARTIAL,
    /** Too little could be assessed (below the configured coverage): no overall score and no recommendation. */
    INCONCLUSIVE
}
