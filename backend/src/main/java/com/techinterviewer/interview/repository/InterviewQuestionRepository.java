package com.techinterviewer.interview.repository;

import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Access to {@link InterviewQuestion}. Questions belong to an interview and carry no user id: the caller first loads
 * the interview through {@code InterviewRepository.findByIdAndCandidateId}, and then asks for its questions by
 * {@code interviewId}. A question id alone is never enough to reach a question.
 */
public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, UUID> {

    /** Access rule: the question {@code id} only if it belongs to {@code interviewId}. */
    Optional<InterviewQuestion> findByIdAndInterviewId(UUID id, UUID interviewId);

    /** Access rule: the questions of {@code interviewId} in the order they were asked. */
    List<InterviewQuestion> findByInterviewIdOrderBySequenceNoAsc(UUID interviewId);

    /** Access rule: the most recently asked question of {@code interviewId}, if any. */
    Optional<InterviewQuestion> findFirstByInterviewIdOrderBySequenceNoDesc(UUID interviewId);

    /**
     * Access rule: the highest sequence number used in {@code interviewId}, or 0 when it has no question yet, so the next
     * question gets {@code latestSequenceNo + 1} ({@code uq_questions_interview_sequence} makes a race fail instead
     * of duplicating).
     */
    @Query("select coalesce(max(q.sequenceNo), 0) from InterviewQuestion q where q.interviewId = :interviewId")
    int findLatestSequenceNo(@Param("interviewId") UUID interviewId);

    /**
     * Access rule: counts the main (not follow-up) questions of {@code section} in {@code interviewId} that have an
     * answer, skipped ones included.
     */
    @Query("""
            select count(q) from InterviewQuestion q join CandidateAnswer a on a.questionId = q.id
            where q.interviewId = :interviewId
              and q.section = :section
              and q.kind <> com.techinterviewer.interview.domain.QuestionKind.FOLLOW_UP""")
    int countAnsweredRootQuestions(@Param("interviewId") UUID interviewId, @Param("section") Section section);

    /**
     * Stores the vector a question is de-duplicated by (columns the entity does not map; AI_ARCHITECTURE.md section 2.9).
     * {@code ck_questions_embedding_model} keeps the vector and its model together.
     */
    @Modifying
    @Query(value = "UPDATE questions SET embedding = CAST(:embedding AS vector), embedding_model = :model WHERE id = :id", nativeQuery = true)
    int storeEmbedding(@Param("id") UUID id, @Param("embedding") String embedding, @Param("model") String model);
}
