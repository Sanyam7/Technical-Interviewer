package com.techinterviewer.interview.service;

import com.techinterviewer.interview.api.dto.SessionResponse;
import com.techinterviewer.interview.domain.Interview;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@code POST /interviews/{id}/questions/next}: the recovery step for an interview whose next question could not be
 * generated when the answer was accepted. It is idempotent: it produces the question when the interview waits for
 * one, and changes nothing when a question is open already, so calling it twice, or from two requests at once, never
 * creates two questions.
 */
@Service
public class NextQuestionService {

    private final DeadlineService deadlines;
    private final InterviewTransactions transactions;
    private final InterviewLoader loader;
    private final QuestionProvisioner provisioner;
    private final SessionAssembler assembler;

    NextQuestionService(
            DeadlineService deadlines,
            InterviewTransactions transactions,
            InterviewLoader loader,
            QuestionProvisioner provisioner,
            SessionAssembler assembler) {
        this.deadlines = deadlines;
        this.transactions = transactions;
        this.loader = loader;
        this.provisioner = provisioner;
        this.assembler = assembler;
    }

    /**
     * @return the session, with the next question as its current question
     * @throws com.techinterviewer.common.error.ApiException {@code 404} (not the caller's interview), {@code 409
     *         ILLEGAL_STATE_TRANSITION} or {@code 409 INTERVIEW_EXPIRED} when the interview asks no questions, {@code 503
     *         AI_PROVIDER_UNAVAILABLE} when the generator cannot produce the question (nothing changed)
     */
    public SessionResponse next(UUID userId, UUID interviewId) {
        boolean expiredNow = deadlines.expireIfDue(interviewId, userId);
        transactions.read(() -> requireAsking(loader.load(interviewId, userId), expiredNow));
        provisioner.provision(userId, interviewId);
        return transactions.read(() -> assembler.session(loader.load(interviewId, userId)));
    }

    /** A refusal when the interview asks no questions at all; whether one is due is decided by the provisioner. */
    private static boolean requireAsking(Interview interview, boolean expiredNow) {
        if (!interview.getState().acceptsAnswers()) {
            throw NotAccepting.error(interview, expiredNow);
        }
        return true;
    }
}
