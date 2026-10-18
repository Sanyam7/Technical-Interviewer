package com.techinterviewer.evaluation.repository;

import com.techinterviewer.evaluation.domain.Evaluation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Access to {@link Evaluation}. An evaluation has no user or interview column of its own: it is reached through its
 * answer, and the interview is always one the caller loaded through {@code InterviewRepository.findByIdAndCandidateId}.
 */
public interface EvaluationRepository extends JpaRepository<Evaluation, UUID> {

    /** Access rule: the evaluation of {@code answerId}; the caller took the answer from an interview it owns. */
    Optional<Evaluation> findByAnswerId(UUID answerId);

    /** Access rule: whether {@code answerId} has been evaluated; the caller took the answer from an interview it owns. */
    boolean existsByAnswerId(UUID answerId);

    /**
     * Access rule: the evaluations of the answers of {@code interviewId}, in the order the answers were submitted
     * (joined through the answer, so evaluations of other interviews never appear).
     */
    @Query("""
            select e from Evaluation e join CandidateAnswer a on a.id = e.answerId
            where a.interviewId = :interviewId
            order by a.submittedAt asc, a.id asc""")
    List<Evaluation> findByInterviewId(@Param("interviewId") UUID interviewId);
}
