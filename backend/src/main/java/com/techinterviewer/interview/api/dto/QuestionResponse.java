package com.techinterviewer.interview.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A question as the candidate sees it. It never carries the expected key points, the rubric, the difficulty, where the
 * text came from or anything the interview remembers about the candidate.
 */
@Schema(description = "A question as the candidate sees it.")
public record QuestionResponse(
        UUID id,
        @Schema(description = "Position in the interview, starting at 1.") int sequenceNo,
        QuestionKind kind,
        @JsonProperty("isFollowUp") @Schema(name = "isFollowUp", description = "True for a follow-up to an earlier question.") boolean isFollowUp,
        Section section,
        @Schema(description = "Absent for the introduction and for coding and design questions without a catalog topic.", nullable = true)
        @Nullable TopicView topic,
        String prompt,
        @Schema(description = "CODE for coding questions and their follow-ups, otherwise TEXT.") AnswerFormat answerFormat,
        @Schema(description = "A suggestion of how long to spend on the question, in minutes. Derived from the plan: a main question gets "
                + "its section's minutes divided by its planned questions, a follow-up half of that.") int suggestedMinutes,
        @Schema(description = "Present for coding questions only.", nullable = true) @Nullable QuestionDetailsResponse details,
        @Schema(description = "True once an answer (or a skip) exists for the question.") boolean answered,
        @Schema(description = "Present for system design questions and their follow-ups only.", nullable = true)
        @Nullable DesignDetailsResponse design,
        @Schema(description = "Present for a question personalised from the candidate's résumé: the experience it is based on. Absent once "
                + "the résumé is deleted.", nullable = true)
        @Nullable ResumeReferenceView resume,
        @Schema(description = "What kind of technical question it is: CONCEPT and INTERNALS (fundamentals), SCENARIO and DEBUGGING (practical), "
                + "EXPERIENCE (the candidate's own work). A follow-up has the type of its question, a follow-up about a claimed experience "
                + "EXPERIENCE. Absent outside the technical section and for questions asked before types existed.", nullable = true)
        @Nullable QuestionType questionType) {

    /** A question without a question type. */
    public QuestionResponse(UUID id, int sequenceNo, QuestionKind kind, boolean isFollowUp, Section section, @Nullable TopicView topic,
            String prompt, AnswerFormat answerFormat, int suggestedMinutes, @Nullable QuestionDetailsResponse details, boolean answered,
            @Nullable DesignDetailsResponse design, @Nullable ResumeReferenceView resume) {
        this(id, sequenceNo, kind, isFollowUp, section, topic, prompt, answerFormat, suggestedMinutes, details, answered, design, resume, null);
    }

    /** A question that is not personalised. */
    public QuestionResponse(UUID id, int sequenceNo, QuestionKind kind, boolean isFollowUp, Section section, @Nullable TopicView topic,
            String prompt, AnswerFormat answerFormat, int suggestedMinutes, @Nullable QuestionDetailsResponse details, boolean answered,
            @Nullable DesignDetailsResponse design) {
        this(id, sequenceNo, kind, isFollowUp, section, topic, prompt, answerFormat, suggestedMinutes, details, answered, design, null);
    }
}
