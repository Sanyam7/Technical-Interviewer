package com.techinterviewer.evaluation.repository;

import com.techinterviewer.evaluation.report.InterviewReport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to {@link InterviewReport}. */
public interface InterviewReportRepository extends JpaRepository<InterviewReport, UUID> {

    /**
     * Access rule: the report of {@code interviewId}, whoever owns it. For the services that have already loaded the
     * interview through {@code InterviewRepository.findByIdAndCandidateId}; never use it with an id from a request.
     */
    Optional<InterviewReport> findByInterviewId(UUID interviewId);

    /** Access rule: the report of {@code interviewId} only if the interview belongs to {@code candidateId}; empty for anybody else's. */
    @Query("""
            select r from InterviewReport r join Interview i on i.id = r.interviewId
            where r.interviewId = :interviewId and i.candidateId = :candidateId""")
    Optional<InterviewReport> findByInterviewIdAndCandidateId(
            @Param("interviewId") UUID interviewId, @Param("candidateId") UUID candidateId);
}
