package com.techinterviewer.interview.service;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Runs a change of one interview in a short transaction: the interview is loaded for its owner, handed to the work and
 * committed with the version check of its row. When another request changed the interview in the meantime the commit
 * fails, nothing of the work survives and the caller gets {@code 409 CONCURRENT_MODIFICATION} carrying the state the
 * interview is in now (decision D7).
 *
 * <p>The work runs inside the transaction and must not call a port. A step that inserts rows of the aggregate without
 * changing the interview row itself has to touch the interview (for example by changing its memory), otherwise the
 * version check does not take part.
 */
@Component
public class InterviewWriter {

    static final String CONCURRENT_DETAIL = "The interview was changed by another request. Reload it and try again.";

    private final InterviewTransactions transactions;
    private final InterviewLoader loader;
    private final InterviewRepository interviews;

    InterviewWriter(InterviewTransactions transactions, InterviewLoader loader, InterviewRepository interviews) {
        this.transactions = transactions;
        this.loader = loader;
        this.interviews = interviews;
    }

    /**
     * @param work what to do with the interview; its result is returned after the commit succeeded
     * @throws ApiException {@code 404} when the interview is not the user's, {@code 409 CONCURRENT_MODIFICATION} on a lost
     *                      race, or whatever the work throws (which rolls the transaction back)
     */
    public <T> T write(UUID interviewId, UUID userId, Function<Interview, T> work) {
        try {
            return transactions.write(() -> work.apply(loader.load(interviewId, userId)));
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw conflict(interviewId, userId, ex);
        }
    }

    /**
     * The {@code 409 CONCURRENT_MODIFICATION} for a lost race on an interview, with the state it has now (read in a
     * transaction of its own, because the one that lost is gone). The state is left out only if the interview has
     * vanished.
     */
    ApiException conflict(UUID interviewId, UUID userId, Throwable cause) {
        ApiException conflict = new ApiException(ErrorCode.CONCURRENT_MODIFICATION, CONCURRENT_DETAIL, cause);
        transactions.read(() -> interviews.findByIdAndCandidateId(interviewId, userId))
                .map(Interview::getState)
                .map(InterviewState::name)
                .ifPresent(state -> conflict.withProperty(TransitionApplier.CURRENT_STATE, state));
        return conflict;
    }
}
