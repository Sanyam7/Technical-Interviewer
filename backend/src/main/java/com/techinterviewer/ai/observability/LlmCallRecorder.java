package com.techinterviewer.ai.observability;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes one {@code llm_calls} row per model call, in its own short transaction, before the caller uses the result:
 * questions, evaluations and reports reference the row by a foreign key, so it must exist first (AI_ARCHITECTURE.md
 * section 10.3).
 *
 * <p>Recording never breaks the AI operation. If the row cannot be written (the database is briefly unavailable, or
 * the interview was deleted meanwhile) the failure is logged and the caller gets no id: the result is stored without
 * a link to its call, which the schema allows.
 */
public class LlmCallRecorder {

    /** The MDC key the correlation filter sets for every request. */
    static final String TRACE_ID = "traceId";

    private static final Logger log = LoggerFactory.getLogger(LlmCallRecorder.class);

    private final LlmCallRepository calls;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public LlmCallRecorder(LlmCallRepository calls, TransactionTemplate transactions, Clock clock) {
        this.calls = calls;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** @return the id of the stored row, or empty when it could not be stored */
    public Optional<UUID> record(LlmCallRecord record) {
        try {
            LlmCall call = new LlmCall(record, MDC.get(TRACE_ID), clock.instant());
            transactions.executeWithoutResult(status -> calls.save(call));
            return Optional.of(call.getId());
        } catch (RuntimeException failure) {
            log.warn("Could not record a {} call to {} ({}); the result is kept without a call record",
                    record.task(), record.provider(), record.status(), failure);
            return Optional.empty();
        }
    }
}
