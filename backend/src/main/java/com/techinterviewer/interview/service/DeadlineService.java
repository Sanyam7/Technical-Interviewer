package com.techinterviewer.interview.service;

import com.techinterviewer.config.AppProperties;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.engine.DeadlinePolicy;
import com.techinterviewer.interview.engine.DeadlineStatus;
import com.techinterviewer.interview.engine.TransitionGuards;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * The lazy deadline (decision D8): nothing runs in the background, so every read of an interview and the answer flow
 * ask this class whether time ran out. An interview that accepts answers whose deadline passed more than the grace
 * period ago is moved to FINAL_EVALUATION with the event TIME_UP and the end reason TIME_UP. Inside the grace period it
 * is left alone: the answer being written when the clock struck is still accepted.
 *
 * <p>The move is recorded as having happened at the deadline, not at the moment somebody noticed it, so the end of
 * the interview does not depend on when the candidate comes back to look.
 */
@Component
public class DeadlineService {

    private static final int MAX_ATTEMPTS = 3;

    private final Clock clock;
    private final Duration grace;
    private final InterviewTransactions transactions;
    private final InterviewLoader loader;
    private final TransitionApplier transitions;
    private final InterviewWriter writer;

    DeadlineService(
            Clock clock,
            AppProperties properties,
            InterviewTransactions transactions,
            InterviewLoader loader,
            TransitionApplier transitions,
            InterviewWriter writer) {
        this.clock = clock;
        this.grace = properties.interview().deadlineGrace();
        this.transactions = transactions;
        this.loader = loader;
        this.transitions = transitions;
        this.writer = writer;
    }

    /** Where the interview stands against its deadline now; RUNNING while it has no deadline (it was not started). */
    public DeadlineStatus status(Interview interview) {
        Instant deadline = interview.getDeadlineAt();
        return deadline == null ? DeadlineStatus.RUNNING : DeadlinePolicy.status(clock.instant(), deadline, grace);
    }

    /** True when the interview still asks questions although its deadline and the grace period are over. */
    public boolean isDue(Interview interview) {
        return interview.getState().acceptsAnswers() && status(interview) == DeadlineStatus.EXPIRED;
    }

    /**
     * Ends the interview with TIME_UP when {@link #isDue} holds, in a transaction of its own. Safe to call from
     * anywhere and any number of times: when another request got there first (or answered in the meantime) the
     * interview is reloaded and judged again, so exactly one TIME_UP is ever recorded.
     *
     * @return true when the interview was ended by this call
     */
    public boolean expireIfDue(UUID interviewId, UUID userId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.write(() -> expire(loader.load(interviewId, userId)));
            } catch (ObjectOptimisticLockingFailureException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    throw writer.conflict(interviewId, userId, ex);
                }
            }
        }
    }

    private boolean expire(Interview interview) {
        if (!isDue(interview)) {
            return false;
        }
        Instant deadline = interview.getDeadlineAt();
        transitions.apply(
                interview,
                transitions.edge(interview, InterviewEvent.TIME_UP, TransitionGuards.NONE),
                deadline,
                Map.of("deadlineAt", deadline.toString(), "detectedAt", clock.instant().toString()));
        return true;
    }
}
