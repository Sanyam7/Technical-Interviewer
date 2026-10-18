package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One row of the audit trail of an interview: the state it left, the state it entered and the event that caused it.
 * Rows are only ever inserted. The id is a database identity (the table is an append-only log, ordered by
 * {@code occurred_at, id}).
 *
 * <p>The creation of an interview is the first row: no source state, destination INIT, event CREATE
 * ({@code ck_interview_state_transitions_creation}). Every other row has a source state and is not a CREATE.
 */
@Entity
@Table(name = "interview_state_transitions")
public class InterviewStateTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "interview_id", nullable = false, updatable = false)
    private UUID interviewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", updatable = false, length = 20)
    private @Nullable InterviewState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, updatable = false, length = 20)
    private InterviewState toState;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, updatable = false, length = 30)
    private InterviewEvent event;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb", updatable = false)
    private @Nullable Map<String, String> details;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected InterviewStateTransition() {
    }

    private InterviewStateTransition(
            UUID interviewId,
            @Nullable InterviewState fromState,
            InterviewState toState,
            InterviewEvent event,
            @Nullable Map<String, String> details,
            Instant occurredAt) {
        this.interviewId = interviewId;
        this.fromState = fromState;
        this.toState = toState;
        this.event = event;
        this.details = details == null || details.isEmpty() ? null : Map.copyOf(details);
        this.occurredAt = occurredAt;
    }

    /** The first row of an interview: nothing to INIT. */
    public static InterviewStateTransition creation(UUID interviewId, Instant occurredAt) {
        return new InterviewStateTransition(
                Require.nonNull(interviewId, "interviewId"),
                null,
                InterviewState.INIT,
                InterviewEvent.CREATE,
                null,
                Require.nonNull(occurredAt, "occurredAt"));
    }

    /**
     * A move from one state to another.
     *
     * @param details optional context of the move (for example why a section ended); empty is stored as absent
     * @throws IllegalArgumentException for a CREATE event or a move into INIT: only {@link #creation} makes those
     */
    public static InterviewStateTransition of(
            UUID interviewId,
            InterviewState from,
            InterviewState to,
            InterviewEvent event,
            @Nullable Map<String, String> details,
            Instant occurredAt) {
        Require.nonNull(interviewId, "interviewId");
        Require.nonNull(from, "from");
        Require.nonNull(to, "to");
        Require.nonNull(event, "event");
        Require.nonNull(occurredAt, "occurredAt");
        if (event == InterviewEvent.CREATE) {
            throw new IllegalArgumentException("a CREATE transition has no source state: use creation()");
        }
        if (to == InterviewState.INIT) {
            throw new IllegalArgumentException("an interview never returns to INIT");
        }
        return new InterviewStateTransition(interviewId, from, to, event, details, occurredAt);
    }

    /** The database id; null until the row is inserted. */
    public @Nullable Long getId() {
        return id;
    }

    public UUID getInterviewId() {
        return interviewId;
    }

    /** The state that was left; null only for the creation row. */
    public @Nullable InterviewState getFromState() {
        return fromState;
    }

    public InterviewState getToState() {
        return toState;
    }

    public InterviewEvent getEvent() {
        return event;
    }

    /** Context of the move, or null when there is none. */
    public @Nullable Map<String, String> getDetails() {
        return details == null ? null : Collections.unmodifiableMap(details);
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
