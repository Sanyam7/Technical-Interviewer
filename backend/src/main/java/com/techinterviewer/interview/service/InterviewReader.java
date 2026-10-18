package com.techinterviewer.interview.service;

import com.techinterviewer.interview.domain.Interview;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Runs a read of one interview: scoped to its owner, in a read-only transaction and after the lazy deadline was
 * applied. If the deadline ran out it is applied first, in a transaction of its own, and the view is built from the
 * interview as it is afterwards, so a client never sees an interview that is still open after its time is up.
 */
@Component
public class InterviewReader {

    private final InterviewTransactions transactions;
    private final InterviewLoader loader;
    private final DeadlineService deadlines;

    InterviewReader(InterviewTransactions transactions, InterviewLoader loader, DeadlineService deadlines) {
        this.transactions = transactions;
        this.loader = loader;
        this.deadlines = deadlines;
    }

    /**
     * @param projection builds the answer from the interview inside the read-only transaction (it may query further)
     * @throws com.techinterviewer.common.error.ApiException {@code 404} when the interview is not the user's
     */
    public <T> T read(UUID interviewId, UUID userId, Function<Interview, T> projection) {
        Optional<T> current = transactions.read(() -> {
            Interview interview = loader.load(interviewId, userId);
            return deadlines.isDue(interview) ? Optional.<T>empty() : Optional.of(projection.apply(interview));
        });
        if (current.isPresent()) {
            return current.get();
        }
        deadlines.expireIfDue(interviewId, userId);
        return transactions.read(() -> projection.apply(loader.load(interviewId, userId)));
    }
}
