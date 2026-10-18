package com.techinterviewer.interview.repository;

import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Access to {@link Interview}. Every lookup that serves a user is scoped by the candidate id, so an interview of
 * somebody else is simply not found (the API answers 404 for it). The inherited {@code findById} is unscoped and is
 * not meant for request handling.
 */
public interface InterviewRepository extends JpaRepository<Interview, UUID> {

    /** Access rule: the interview {@code id} if it belongs to {@code candidateId}; empty for anybody else's. */
    Optional<Interview> findByIdAndCandidateId(UUID id, UUID candidateId);

    /**
     * Like {@link #findByIdAndCandidateId} and additionally forces the interview's version to increase when the
     * transaction commits, even if nothing in the row changed. It is how a step that only inserts rows of the
     * aggregate (an answer) takes part in optimistic locking: two such steps on the same version cannot both commit.
     * Access rule: same as {@link #findByIdAndCandidateId}. Must run inside a transaction.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select i from Interview i where i.id = :id and i.candidateId = :candidateId")
    Optional<Interview> findAndBumpVersionByIdAndCandidateId(@Param("id") UUID id, @Param("candidateId") UUID candidateId);

    /** Access rule: only interviews of {@code candidateId}, newest first (ties broken by id, which is time ordered). */
    @Query("select i from Interview i where i.candidateId = :candidateId order by i.createdAt desc, i.id desc")
    Page<Interview> findPageByCandidateId(@Param("candidateId") UUID candidateId, Pageable pageable);

    /** Access rule: only interviews of {@code candidateId} that are in {@code state}, newest first. */
    @Query("select i from Interview i where i.candidateId = :candidateId and i.state = :state order by i.createdAt desc, i.id desc")
    Page<Interview> findPageByCandidateIdAndState(
            @Param("candidateId") UUID candidateId, @Param("state") InterviewState state, Pageable pageable);

    /**
     * Access rule: counts only interviews of {@code candidateId} created at or after {@code since}. The daily limit
     * counts from the database, so it holds across restarts and instances.
     */
    long countByCandidateIdAndCreatedAtGreaterThanEqual(UUID candidateId, Instant since);

    /**
     * Whether any interview uses the configuration. Not scoped by user: callers ask only after they resolved the
     * configuration through {@code InterviewConfigurationRepository.findByIdAndOwnerUserId}.
     */
    boolean existsByConfigurationId(UUID configurationId);

    /**
     * Takes a transaction-scoped advisory lock on {@code key} and waits until it is free: the lock is released when the
     * transaction ends. The interview creation takes it for the candidate before it counts the day's interviews, so two
     * creations of one candidate run one after the other and the daily limit cannot be overrun by a race. The result
     * is only there because a native query needs one. Must run inside a transaction. Access rule: touches no row.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(:key)) as acquired", nativeQuery = true)
    long lockByKey(@Param("key") long key);
}
