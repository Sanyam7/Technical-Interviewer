package com.techinterviewer.profile;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProfileRepository extends JpaRepository<CandidateProfile, UUID> {

    Optional<CandidateProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /** Locks the row until the transaction ends, so two replacements of one profile run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CandidateProfile p where p.userId = :userId")
    Optional<CandidateProfile> findByUserIdForUpdate(@Param("userId") UUID userId);
}
