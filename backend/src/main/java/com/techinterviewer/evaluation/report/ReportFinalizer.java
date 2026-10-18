package com.techinterviewer.evaluation.report;

import java.util.UUID;

/**
 * Closes an interview once its questions are over: evaluates answers that are still pending, computes the report
 * and moves the interview to COMPLETED. The interview use cases call it from POST /interviews/{id}/finish; the
 * report feature implements it. Every call is idempotent and safe to repeat after a failure.
 *
 * <p>The interview must already be in FINAL_EVALUATION (or COMPLETED, in which case nothing happens). A failure
 * of the AI layer is not an exception: it is reported as a retryable {@link FinalizationResult}.
 */
public interface ReportFinalizer {

    /**
     * @param interviewId the interview, already loaded by the caller through an ownership-scoped query
     * @param candidateId the authenticated owner; implementations re-check ownership and answer 404 for others
     */
    FinalizationResult finalizeInterview(UUID interviewId, UUID candidateId);
}
