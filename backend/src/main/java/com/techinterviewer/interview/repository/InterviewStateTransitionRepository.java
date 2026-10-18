package com.techinterviewer.interview.repository;

import com.techinterviewer.interview.domain.InterviewStateTransition;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to the audit trail {@link InterviewStateTransition}. Rows are only inserted and read, never changed. */
public interface InterviewStateTransitionRepository extends JpaRepository<InterviewStateTransition, Long> {

    /** Access rule: the transitions of {@code interviewId} in the order they happened; the caller owns the interview. */
    List<InterviewStateTransition> findByInterviewIdOrderByOccurredAtAscIdAsc(UUID interviewId);
}
