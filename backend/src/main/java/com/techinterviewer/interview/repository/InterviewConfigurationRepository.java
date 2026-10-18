package com.techinterviewer.interview.repository;

import com.techinterviewer.interview.domain.InterviewConfiguration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to {@link InterviewConfiguration}. Every lookup that serves a user is scoped by the owner id. */
public interface InterviewConfigurationRepository extends JpaRepository<InterviewConfiguration, UUID> {

    /** Access rule: the configuration {@code id} if {@code ownerUserId} owns it; empty for anybody else's. */
    Optional<InterviewConfiguration> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    /** Access rule: only configurations owned by {@code ownerUserId}, newest first (ties broken by id). */
    @Query("select c from InterviewConfiguration c where c.ownerUserId = :ownerUserId order by c.createdAt desc, c.id desc")
    Page<InterviewConfiguration> findPageByOwnerUserId(@Param("ownerUserId") UUID ownerUserId, Pageable pageable);

    /** Access rule: the configurations among {@code ids} that {@code ownerUserId} owns; anybody else's are left out. */
    List<InterviewConfiguration> findByOwnerUserIdAndIdIn(UUID ownerUserId, Collection<UUID> ids);

    /**
     * Deletes the configuration {@code id} if {@code ownerUserId} owns it, and returns how many were deleted (0 for
     * anybody else's). The database refuses to delete a configuration an interview uses
     * ({@code fk_interviews_configuration}); the caller checks {@code InterviewRepository.existsByConfigurationId}
     * first to answer with a proper error. Must run inside a transaction.
     */
    long deleteByIdAndOwnerUserId(UUID id, UUID ownerUserId);
}
