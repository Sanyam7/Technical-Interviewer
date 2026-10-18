package com.techinterviewer.evaluation.support;

import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.NarrativeItem;
import com.techinterviewer.interview.port.NarrativeRequest;
import com.techinterviewer.interview.port.ReportNarrative;
import com.techinterviewer.interview.port.ReportNarrator;
import com.techinterviewer.persistence.Fixtures;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A test double of the {@link ReportNarrator} port. Unless scripted it writes a narrative that cites the first and the
 * last evaluation it was given. Records every request and can be switched off.
 */
public final class ScriptedReportNarrator implements ReportNarrator {

    private final List<NarrativeRequest> requests = new CopyOnWriteArrayList<>();
    private volatile boolean available = true;
    private volatile @Nullable Function<NarrativeRequest, ReportNarrative> script;
    private volatile @Nullable Runnable beforeAnswering;
    private volatile @Nullable UUID llmCallId = Fixtures.REPORT_LLM_CALL;
    private final List<String> transactionViolations = new CopyOnWriteArrayList<>();

    public void reset() {
        requests.clear();
        available = true;
        script = null;
        beforeAnswering = null;
        llmCallId = Fixtures.REPORT_LLM_CALL;
        transactionViolations.clear();
    }

    /** A database transaction (or its synchronisation) was open during a call: must stay empty. */
    public List<String> transactionViolations() {
        return List.copyOf(transactionViolations);
    }

    public void unavailable() {
        available = false;
    }

    /** The model call the standard narrative cites; null when the test database has no llm_calls row for it. */
    public void llmCallId(@Nullable UUID llmCallId) {
        this.llmCallId = llmCallId;
    }

    public void script(Function<NarrativeRequest, ReportNarrative> script) {
        this.script = script;
    }

    /** Runs before each answer is produced (a barrier to line two callers up, for instance). */
    public void beforeAnswering(Runnable action) {
        this.beforeAnswering = action;
    }

    public List<NarrativeRequest> requests() {
        return List.copyOf(requests);
    }

    public int calls() {
        return requests.size();
    }

    @Override
    public ReportNarrative narrate(NarrativeRequest request) {
        requests.add(request);
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) {
            transactionViolations.add("narrate(" + request.interviewId() + ")");
        }
        Runnable hook = beforeAnswering;
        if (hook != null) {
            hook.run();
        }
        if (!available) {
            throw new AiUnavailableException("scripted outage");
        }
        Function<NarrativeRequest, ReportNarrative> scripted = script;
        return scripted != null ? scripted.apply(request) : standard(request);
    }

    private ReportNarrative standard(NarrativeRequest request) {
        var first = request.evaluations().get(0).evaluationId();
        var last = request.evaluations().get(request.evaluations().size() - 1).evaluationId();
        return new ReportNarrative(
                "A scripted summary.",
                List.of(new NarrativeItem("Strong first answer", List.of(first))),
                List.of(new NarrativeItem("Weak last answer", List.of(last))),
                List.of("Practice more"),
                "report-1.0",
                llmCallId);
    }
}
