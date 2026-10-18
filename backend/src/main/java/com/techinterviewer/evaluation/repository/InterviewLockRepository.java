package com.techinterviewer.evaluation.repository;

import com.techinterviewer.interview.domain.Interview;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The one lock the report feature needs on an interview row: it serialises the steps that decide the outcome of the
 * final evaluation (claiming a generation attempt, publishing the report, recording a failure), so two requests that
 * finalise the same interview can never both write a result. The lock is held only for the short transaction that asks
 * for it, never across a call to an AI port.
 */
public interface InterviewLockRepository extends Repository<Interview, UUID> {

    /**
     * Access rule: the interview {@code id} if it belongs to {@code candidateId}, empty for anybody else's. The row is
     * locked ({@code SELECT ... FOR UPDATE}) until the surrounding transaction ends; a concurrent caller waits and
     * then sees what the first one committed. Must run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Interview i where i.id = :id and i.candidateId = :candidateId")
    Optional<Interview> lockByIdAndCandidateId(@Param("id") UUID id, @Param("candidateId") UUID candidateId);
}
