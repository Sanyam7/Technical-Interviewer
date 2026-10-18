package com.techinterviewer.resume;

import com.techinterviewer.resume.domain.CandidateResume;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ResumeRepository extends JpaRepository<CandidateResume, UUID> {

    Optional<CandidateResume> findByUserId(UUID userId);

    /** Removes the user's résumé; interviews linked to it keep running without personalisation (their link becomes NULL). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CandidateResume r where r.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
