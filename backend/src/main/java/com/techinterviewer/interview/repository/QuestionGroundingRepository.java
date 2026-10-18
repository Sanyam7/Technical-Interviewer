package com.techinterviewer.interview.repository;

import com.techinterviewer.interview.domain.QuestionGrounding;
import com.techinterviewer.interview.domain.QuestionGroundingId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to {@link QuestionGrounding}. Grounding is server-side material; the caller owns the question's interview. */
public interface QuestionGroundingRepository extends JpaRepository<QuestionGrounding, QuestionGroundingId> {

    /** Access rule: the chunks that grounded {@code questionId}, best ranked first. */
    List<QuestionGrounding> findByIdQuestionIdOrderByRankAsc(UUID questionId);
}
