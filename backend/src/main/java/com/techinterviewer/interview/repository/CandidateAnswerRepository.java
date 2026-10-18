package com.techinterviewer.interview.repository;

import com.techinterviewer.interview.domain.CandidateAnswer;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Access to {@link CandidateAnswer}. An answer carries the id of its interview, and every lookup is scoped by it: the
 * caller first loads the interview through {@code InterviewRepository.findByIdAndCandidateId}.
 */
public interface CandidateAnswerRepository extends JpaRepository<CandidateAnswer, UUID> {

    /** Access rule: the answer to {@code questionId} only if it belongs to {@code interviewId}; the idempotency lookup of a submission. */
    Optional<CandidateAnswer> findByQuestionIdAndInterviewId(UUID questionId, UUID interviewId);

    /** Access rule: the answer {@code id} only if it belongs to {@code interviewId}. */
    Optional<CandidateAnswer> findByIdAndInterviewId(UUID id, UUID interviewId);

    /**
     * Access rule: the answer {@code id} of {@code interviewId}, locked against concurrent changes of its evaluation state
     * (a row lock held until the transaction ends). Every change of the attempt counter, the lease and the status goes
     * through this lookup, which is what makes the retry limit exact under concurrent requests.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CandidateAnswer a where a.id = :id and a.interviewId = :interviewId")
    Optional<CandidateAnswer> findByIdAndInterviewIdForUpdate(@Param("id") UUID id, @Param("interviewId") UUID interviewId);

    /** Access rule: the answers of {@code interviewId} in the order they were submitted. */
    List<CandidateAnswer> findByInterviewIdOrderBySubmittedAtAscIdAsc(UUID interviewId);

    /** Access rule: whether {@code questionId} has an answer in {@code interviewId}. */
    boolean existsByQuestionIdAndInterviewId(UUID questionId, UUID interviewId);

    /** Access rule: the ids of the questions of {@code interviewId} that have an answer, skipped ones included. */
    @Query("select a.questionId from CandidateAnswer a where a.interviewId = :interviewId")
    List<UUID> findAnsweredQuestionIds(@Param("interviewId") UUID interviewId);

    /** Access rule: counts the answers of {@code interviewId}, skipped ones included. */
    long countByInterviewId(UUID interviewId);

    /** Access rule: counts the answers of {@code interviewId} in the given evaluation status. */
    long countByInterviewIdAndEvaluationStatus(UUID interviewId, com.techinterviewer.interview.domain.EvaluationStatus status);

    /**
     * Access rule: ids of the answers of {@code interviewId} that still need an evaluation: PENDING (a skipped answer is
     * COMPLETED, a given-up one UNASSESSABLE), oldest first. Served by the partial index {@code ix_answers_pending}.
     */
    @Query("""
            select a.id from CandidateAnswer a
            where a.interviewId = :interviewId
              and a.skipped = false
              and a.evaluationStatus = com.techinterviewer.interview.domain.EvaluationStatus.PENDING
            order by a.submittedAt asc, a.id asc""")
    List<UUID> findPendingIdsByInterviewId(@Param("interviewId") UUID interviewId);
}
