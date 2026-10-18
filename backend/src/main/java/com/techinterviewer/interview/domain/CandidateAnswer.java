package com.techinterviewer.interview.domain;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a candidate answered to one question (table {@code answers}). At most one answer exists per question
 * ({@code uq_answers_question}), which is what makes answer submission idempotent.
 *
 * <p>The content never changes. Only the evaluation state moves: a submitted answer is PENDING until an evaluation was
 * stored ({@link #markEvaluated()}) or it was given up ({@link #giveUp}, UNASSESSABLE). An attempt is claimed
 * ({@link #startAttempt}) before the evaluator is called and either succeeds or is recorded as failed ({@link #failAttempt});
 * the attempt counter and the {@code nextEvaluationAt} instant are stored, so the limit and the backoff survive a restart
 * and two requests never evaluate the same answer at once. A skipped answer has nothing to evaluate and is COMPLETED from
 * the start, so the "answers still to evaluate" lookups pass over it; the report scores it 0 by reading {@link #isSkipped()}.
 * That {@code interviewId} is the interview of the question is checked by the service that has both at hand.
 */
@Entity
@Table(name = "answers")
public class CandidateAnswer extends BaseEntity {

    public static final int MAX_CONTENT_LENGTH = 20_000;
    public static final int MAX_CODE_LANGUAGE_LENGTH = 20;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "interview_id", nullable = false, updatable = false)
    private UUID interviewId;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "code_language", updatable = false, length = MAX_CODE_LANGUAGE_LENGTH)
    private @Nullable String codeLanguage;

    @Column(name = "skipped", nullable = false, updatable = false)
    private boolean skipped;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "architecture", columnDefinition = "jsonb", updatable = false)
    private @Nullable ArchitectureDiagram architecture;

    @Column(name = "time_taken_seconds", updatable = false)
    private @Nullable Integer timeTakenSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_status", nullable = false, length = 20)
    private EvaluationStatus evaluationStatus;

    @Column(name = "evaluation_attempts", nullable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int evaluationAttempts;

    @Column(name = "next_evaluation_at")
    private @Nullable Instant nextEvaluationAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_failure", length = 20)
    private @Nullable EvaluationFailure evaluationFailure;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    protected CandidateAnswer() {
    }

    private CandidateAnswer(
            UUID questionId,
            UUID interviewId,
            String content,
            @Nullable String codeLanguage,
            boolean skipped,
            @Nullable Integer timeTakenSeconds,
            EvaluationStatus evaluationStatus,
            Instant submittedAt) {
        this.questionId = questionId;
        this.interviewId = interviewId;
        this.content = content;
        this.codeLanguage = codeLanguage;
        this.skipped = skipped;
        this.timeTakenSeconds = timeTakenSeconds;
        this.evaluationStatus = evaluationStatus;
        this.submittedAt = submittedAt;
    }

    /**
     * An answer to a system design question that awaits evaluation, with the architecture diagram the candidate drew (absent when
     * they drew none).
     */
    public static CandidateAnswer submitDesign(
            UUID questionId,
            UUID interviewId,
            String content,
            @Nullable ArchitectureDiagram architecture,
            @Nullable Integer timeTakenSeconds,
            Instant now) {
        CandidateAnswer answer = submit(questionId, interviewId, content, null, timeTakenSeconds, now);
        answer.architecture = architecture;
        return answer;
    }

    /**
     * An answer that awaits evaluation.
     *
     * @param content          not blank and at most {@value #MAX_CONTENT_LENGTH} characters
     * @param codeLanguage     the language of a code answer, absent for text
     * @param timeTakenSeconds how long the candidate took, if known; not negative
     */
    public static CandidateAnswer submit(
            UUID questionId,
            UUID interviewId,
            String content,
            @Nullable String codeLanguage,
            @Nullable Integer timeTakenSeconds,
            Instant now) {
        Require.nonNull(content, "content");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank: use skip() to skip a question");
        }
        return build(questionId, interviewId, content, codeLanguage, false, timeTakenSeconds, EvaluationStatus.PENDING, now);
    }

    /** A skipped question: empty content, nothing to evaluate. */
    public static CandidateAnswer skip(UUID questionId, UUID interviewId, @Nullable Integer timeTakenSeconds, Instant now) {
        return build(questionId, interviewId, "", null, true, timeTakenSeconds, EvaluationStatus.COMPLETED, now);
    }

    private static CandidateAnswer build(
            UUID questionId,
            UUID interviewId,
            String content,
            @Nullable String codeLanguage,
            boolean skipped,
            @Nullable Integer timeTakenSeconds,
            EvaluationStatus status,
            Instant now) {
        Require.nonNull(questionId, "questionId");
        Require.nonNull(interviewId, "interviewId");
        Require.nonNull(now, "now");
        Require.maxLength(content, "content", MAX_CONTENT_LENGTH);
        String language = Require.nullOrNotBlank(codeLanguage, "codeLanguage", MAX_CODE_LANGUAGE_LENGTH);
        if (timeTakenSeconds != null) {
            Require.atLeast(timeTakenSeconds, 0, "timeTakenSeconds");
        }
        return new CandidateAnswer(questionId, interviewId, content, language, skipped, timeTakenSeconds, status, now);
    }

    /** The architecture diagram submitted with a design answer; null for every other answer and when none was drawn. */
    public @Nullable ArchitectureDiagram getArchitecture() {
        return architecture;
    }

    /**
     * The evaluation of this answer was stored.
     *
     * @throws IllegalStateException when the answer is not PENDING: it is evaluated already (a skipped answer is), or given up
     */
    public void markEvaluated() {
        requirePending("be evaluated");
        this.evaluationStatus = EvaluationStatus.COMPLETED;
        this.nextEvaluationAt = null;
        this.evaluationFailure = null;
    }

    /**
     * An attempt to evaluate the answer starts. Only one attempt can be in progress: until {@code leaseEnd} no other may start.
     *
     * @param now      the current instant
     * @param leaseEnd when the attempt is considered lost (a crashed process) and another may start; after {@code now}. Clears
     *                 the failure of the attempt before: {@code evaluationFailure} is the outcome of the latest attempt, null while it runs
     * @return the number of the attempt, from 1
     * @throws IllegalStateException when the answer is not PENDING, an attempt is in progress, or it is a skipped answer
     */
    public int startAttempt(Instant now, Instant leaseEnd) {
        requirePending("start an attempt");
        if (!canStartAttempt(now)) {
            throw new IllegalStateException("answer " + getId() + " is not due for an attempt before " + nextEvaluationAt);
        }
        if (!leaseEnd.isAfter(now)) {
            throw new IllegalArgumentException("leaseEnd must be after now");
        }
        this.evaluationAttempts++;
        this.nextEvaluationAt = leaseEnd;
        this.evaluationFailure = null;
        return evaluationAttempts;
    }

    /** True when the answer is PENDING and no attempt is in progress or backing off at {@code now}. */
    public boolean canStartAttempt(Instant now) {
        return evaluationStatus == EvaluationStatus.PENDING && (nextEvaluationAt == null || !nextEvaluationAt.isAfter(now));
    }

    /** True when the latest attempt was started and has not been reported as failed: it is in progress (or was lost). */
    public boolean attemptInProgress() {
        return evaluationStatus == EvaluationStatus.PENDING && evaluationAttempts > 0 && evaluationFailure == null;
    }

    /**
     * Attempt number {@code attempt} failed; another one may start at {@code retryAt}. Starting an attempt clears the failure of
     * the one before, so an attempt can be reported once.
     *
     * @throws IllegalStateException when the answer is not PENDING, {@code attempt} is not the latest attempt, or it was reported already
     */
    public void failAttempt(int attempt, EvaluationFailure failure, Instant retryAt) {
        requireLatestAttempt(attempt);
        if (evaluationFailure != null) {
            throw new IllegalStateException("attempt " + attempt + " of answer " + getId() + " was reported already");
        }
        this.evaluationFailure = Require.nonNull(failure, "failure");
        this.nextEvaluationAt = Require.nonNull(retryAt, "retryAt");
    }

    /**
     * No more attempts: the answer cannot be assessed. Final, and not a judgement of the answer.
     *
     * @throws IllegalStateException when the answer is not PENDING
     */
    public void giveUp(EvaluationFailure reason) {
        requirePending("be given up");
        this.evaluationStatus = EvaluationStatus.UNASSESSABLE;
        this.evaluationFailure = Require.nonNull(reason, "reason");
        this.nextEvaluationAt = null;
    }

    private void requireLatestAttempt(int attempt) {
        requirePending("record a failed attempt");
        if (attempt != evaluationAttempts) {
            throw new IllegalStateException("answer " + getId() + " is at attempt " + evaluationAttempts + ", not " + attempt);
        }
    }

    private void requirePending(String action) {
        if (evaluationStatus != EvaluationStatus.PENDING) {
            throw new IllegalStateException("answer " + getId() + " is " + evaluationStatus + " and cannot " + action);
        }
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public UUID getInterviewId() {
        return interviewId;
    }

    public String getContent() {
        return content;
    }

    public @Nullable String getCodeLanguage() {
        return codeLanguage;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public @Nullable Integer getTimeTakenSeconds() {
        return timeTakenSeconds;
    }

    /** Attempts started so far. */
    public int getEvaluationAttempts() {
        return evaluationAttempts;
    }

    /** No attempt may start before this instant (backoff, or the lease of an attempt in progress); null when none is pending. */
    public @Nullable Instant getNextEvaluationAt() {
        return nextEvaluationAt;
    }

    /** Why the last attempt failed, or why the answer was given up; null when no attempt failed. */
    public @Nullable EvaluationFailure getEvaluationFailure() {
        return evaluationFailure;
    }

    public EvaluationStatus getEvaluationStatus() {
        return evaluationStatus;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
