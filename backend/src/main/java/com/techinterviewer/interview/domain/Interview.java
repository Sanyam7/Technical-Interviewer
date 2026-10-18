package com.techinterviewer.interview.domain;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The aggregate root of an interview session: its state, deadline, plan and memory. Every mutation goes through a
 * method that guards the invariants; there are no setters.
 *
 * <ul>
 *   <li><b>Plan</b>: written once at creation and never updated (not even by a mapping change).
 *   <li><b>State</b>: changed only by {@link #applyTransition}, which checks that the caller saw the current state
 *       and refuses to leave a terminal state. Whether a move is <i>legal</i> is decided by the state machine of the
 *       engine; the entity only protects its own consistency (timestamps, end reason, resume state, current question).
 *   <li><b>Memory</b>: replaced as a whole value, never edited in place.
 *   <li><b>Concurrency</b>: {@code version} is a JPA version, so two writers of the same row cannot both succeed.
 * </ul>
 *
 * <p>Every mutator takes the time from the caller (the application clock): the entity never reads a clock.
 * References to other aggregates (candidate, configuration, current question) are plain ids.
 */
@Entity
@Table(name = "interviews")
public class Interview extends BaseEntity {

    /** The section states a follow-up thread hangs below, and therefore the states {@code resumeState} can hold. */
    private static final Set<InterviewState> RESUMABLE = Set.of(
            InterviewState.QUESTIONING, InterviewState.CODING, InterviewState.SYSTEM_DESIGN);

    @Column(name = "candidate_id", nullable = false, updatable = false)
    private UUID candidateId;

    @Column(name = "configuration_id", nullable = false, updatable = false)
    private UUID configurationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private InterviewState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "resume_state", length = 20)
    private @Nullable InterviewState resumeState;

    @Column(name = "current_question_id")
    private @Nullable UUID currentQuestionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan", columnDefinition = "jsonb", nullable = false, updatable = false)
    private InterviewPlan plan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memory", columnDefinition = "jsonb", nullable = false)
    private InterviewMemory memory;

    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    @Column(name = "deadline_at")
    private @Nullable Instant deadlineAt;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 20)
    private @Nullable EndReason endReason;

    /**
     * The résumé the interview is personalised from. Never written after creation: the database sets it to NULL when the résumé is
     * deleted or replaced, and a later save must not restore the old id.
     */
    @Column(name = "resume_id", updatable = false)
    private @Nullable UUID resumeId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Interview() {
    }

    private Interview(UUID candidateId, UUID configurationId, InterviewPlan plan, @Nullable UUID resumeId, Instant now) {
        this.candidateId = candidateId;
        this.configurationId = configurationId;
        this.resumeId = resumeId;
        this.state = InterviewState.INIT;
        this.plan = plan;
        this.memory = InterviewMemory.empty();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * A new interview in state INIT with empty memory. The caller records the first audit row with
     * {@link InterviewStateTransition#creation}.
     */
    public static Interview create(UUID candidateId, UUID configurationId, InterviewPlan plan, Instant now) {
        return create(candidateId, configurationId, plan, null, now);
    }

    /** A new interview personalised from résumé {@code resumeId} (null: not personalised). */
    public static Interview create(UUID candidateId, UUID configurationId, InterviewPlan plan, @Nullable UUID resumeId, Instant now) {
        return new Interview(
                Require.nonNull(candidateId, "candidateId"),
                Require.nonNull(configurationId, "configurationId"),
                Require.nonNull(plan, "plan"),
                resumeId,
                Require.nonNull(now, "now"));
    }

    /**
     * Starts the clock: sets {@code startedAt} and {@code deadlineAt = now + durationMinutes}. It can happen once,
     * while the interview is in INIT. The state itself moves with {@link #applyTransition}.
     *
     * @throws IllegalStateException when the interview was started already or has left INIT
     */
    public void start(Instant now, int durationMinutes) {
        Require.nonNull(now, "now");
        Require.atLeast(durationMinutes, 1, "durationMinutes");
        if (state != InterviewState.INIT || startedAt != null) {
            throw new IllegalStateException("interview " + getId() + " was started already (state " + state + ")");
        }
        this.startedAt = now;
        this.deadlineAt = now.plus(Duration.ofMinutes(durationMinutes));
        this.updatedAt = now;
    }

    /** {@link #applyTransition(InterviewState, InterviewState, InterviewEvent, Map, Instant)} without details. */
    public InterviewStateTransition applyTransition(
            InterviewState expectedFrom, InterviewState to, InterviewEvent event, Instant now) {
        return applyTransition(expectedFrom, to, event, null, now);
    }

    /**
     * Moves the interview to {@code to} and returns the audit row of the move, which the caller must save in the
     * same transaction. Guards (each failure leaves the interview untouched):
     *
     * <ul>
     *   <li>{@code expectedFrom} must be the current state (the caller decided on a state it no longer is in);
     *   <li>a terminal state (COMPLETED, ABANDONED) is never left;
     *   <li>the target is never INIT and the event is never CREATE (creation is not a transition);
     *   <li>only ABANDONED may be entered before {@link #start} was called;
     *   <li>COMPLETED requires that the interview already ended (it passed FINAL_EVALUATION).
     * </ul>
     *
     * Effects: entering FINAL_EVALUATION or ABANDONED sets {@code endedAt} and the {@code endReason} (TIME_UP for the
     * TIME_UP event, CANDIDATE_ENDED for END_REQUESTED, ABANDONED for ABANDONED, otherwise PLAN_COMPLETE); staying in
     * FINAL_EVALUATION (a failed report) keeps them. Entering FOLLOW_UP remembers the section to return to
     * ({@code resumeState}); leaving FOLLOW_UP forgets it. The current question is cleared: the question that
     * was current is resolved by the transition, and the next one is set with {@link #askQuestion}.
     *
     * @throws IllegalStateException when a guard fails
     */
    public InterviewStateTransition applyTransition(
            InterviewState expectedFrom,
            InterviewState to,
            InterviewEvent event,
            @Nullable Map<String, String> details,
            Instant now) {
        Require.nonNull(expectedFrom, "expectedFrom");
        Require.nonNull(to, "to");
        Require.nonNull(event, "event");
        Require.nonNull(now, "now");
        if (state != expectedFrom) {
            throw new IllegalStateException(
                    "interview " + getId() + " is in state " + state + ", not in the expected state " + expectedFrom);
        }
        requireNotTerminal();
        if (to == InterviewState.INIT || event == InterviewEvent.CREATE) {
            throw new IllegalStateException("an interview is created in INIT; it cannot move to INIT or be created again");
        }
        if (startedAt == null && to != InterviewState.ABANDONED) {
            throw new IllegalStateException("interview " + getId() + " has not been started: it can only be abandoned");
        }
        if (to == InterviewState.COMPLETED && endedAt == null) {
            throw new IllegalStateException("interview " + getId() + " cannot complete before it ended");
        }
        if (to == InterviewState.FOLLOW_UP && state != InterviewState.FOLLOW_UP && !RESUMABLE.contains(state)) {
            throw new IllegalStateException("a follow-up cannot start from state " + state);
        }

        InterviewStateTransition row = InterviewStateTransition.of(getId(), state, to, event, details, now);
        boolean ends = (to == InterviewState.FINAL_EVALUATION || to == InterviewState.ABANDONED) && to != state;
        if (ends) {
            this.endedAt = now;
            this.endReason = endReasonOf(to, event);
        }
        if (to == InterviewState.FOLLOW_UP) {
            this.resumeState = state == InterviewState.FOLLOW_UP ? resumeState : state;
        } else {
            this.resumeState = null;
        }
        this.state = to;
        this.currentQuestionId = null;
        this.updatedAt = now;
        return row;
    }

    private static EndReason endReasonOf(InterviewState entered, InterviewEvent event) {
        if (entered == InterviewState.ABANDONED) {
            return EndReason.ABANDONED;
        }
        return switch (event) {
            case TIME_UP -> EndReason.TIME_UP;
            case END_REQUESTED -> EndReason.CANDIDATE_ENDED;
            default -> EndReason.PLAN_COMPLETE;
        };
    }

    /** Replaces the whole memory (it is rewritten after every evaluation, never edited in place). */
    public void replaceMemory(InterviewMemory newMemory, Instant now) {
        Require.nonNull(newMemory, "memory");
        Require.nonNull(now, "now");
        requireNotTerminal();
        this.memory = newMemory;
        this.updatedAt = now;
    }

    /**
     * Makes {@code questionId} the question the candidate is answering.
     *
     * @throws IllegalStateException when the interview is not in a state that asks questions
     */
    public void askQuestion(UUID questionId, Instant now) {
        Require.nonNull(questionId, "questionId");
        Require.nonNull(now, "now");
        if (!state.acceptsAnswers()) {
            throw new IllegalStateException("no question can be asked in state " + state);
        }
        this.currentQuestionId = questionId;
        this.updatedAt = now;
    }

    /** Leaves the interview without a current question (the next one is not generated yet). */
    public void clearCurrentQuestion(Instant now) {
        Require.nonNull(now, "now");
        requireNotTerminal();
        this.currentQuestionId = null;
        this.updatedAt = now;
    }

    private void requireNotTerminal() {
        if (state.isTerminal()) {
            throw new IllegalStateException("interview " + getId() + " is " + state + " and can no longer change");
        }
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public UUID getCandidateId() {
        return candidateId;
    }

    public UUID getConfigurationId() {
        return configurationId;
    }

    /** The résumé the interview is personalised from; null when it is not, or once that résumé was deleted or replaced. */
    public @Nullable UUID getResumeId() {
        return resumeId;
    }

    public InterviewState getState() {
        return state;
    }

    /** The section state to return to after FOLLOW_UP; null outside a follow-up. */
    public @Nullable InterviewState getResumeState() {
        return resumeState;
    }

    public @Nullable UUID getCurrentQuestionId() {
        return currentQuestionId;
    }

    public InterviewPlan getPlan() {
        return plan;
    }

    public InterviewMemory getMemory() {
        return memory;
    }

    public @Nullable Instant getStartedAt() {
        return startedAt;
    }

    public @Nullable Instant getDeadlineAt() {
        return deadlineAt;
    }

    public @Nullable Instant getEndedAt() {
        return endedAt;
    }

    public @Nullable EndReason getEndReason() {
        return endReason;
    }

    /** The optimistic-locking counter: it increases with every update of the row. */
    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
