package com.techinterviewer.interview.service;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one way the interview use cases reach an interview: scoped by the authenticated user. An interview, a
 * configuration or a question that belongs to somebody else is reported exactly like one that does not exist
 * ({@code 404 NOT_FOUND}), so an id reveals nothing about other users.
 *
 * <p>Call it inside the transaction of the use case: the entities it returns are managed by that transaction.
 */
@Component
public class InterviewLoader {

    static final String INTERVIEW_NOT_FOUND = "Interview not found.";
    static final String CONFIGURATION_NOT_FOUND = "Configuration not found.";
    static final String QUESTION_NOT_FOUND = "Question not found.";

    private final InterviewRepository interviews;
    private final InterviewConfigurationRepository configurations;
    private final InterviewQuestionRepository questions;

    InterviewLoader(InterviewRepository interviews, InterviewConfigurationRepository configurations, InterviewQuestionRepository questions) {
        this.interviews = interviews;
        this.configurations = configurations;
        this.questions = questions;
    }

    /** The interview of {@code userId}; {@code 404} for a missing interview and for somebody else's. */
    public Interview load(UUID interviewId, UUID userId) {
        return interviews.findByIdAndCandidateId(interviewId, userId).orElseThrow(() -> notFound(INTERVIEW_NOT_FOUND));
    }

    /** The configuration of {@code userId}; {@code 404} for a missing configuration and for somebody else's. */
    public InterviewConfiguration loadConfiguration(UUID configurationId, UUID userId) {
        return configurations.findByIdAndOwnerUserId(configurationId, userId).orElseThrow(() -> notFound(CONFIGURATION_NOT_FOUND));
    }

    /** The configuration an interview was created from. It always exists: an interview keeps its configuration alive. */
    public InterviewConfiguration configurationOf(Interview interview) {
        return configurations.findByIdAndOwnerUserId(interview.getConfigurationId(), interview.getCandidateId())
                .orElseThrow(() -> new IllegalStateException(
                        "interview " + interview.getId() + " has no configuration " + interview.getConfigurationId()));
    }

    /** A question of an interview the caller already loaded; {@code 404} for a question of any other interview. */
    public InterviewQuestion loadQuestion(Interview interview, UUID questionId) {
        return questions.findByIdAndInterviewId(questionId, interview.getId()).orElseThrow(() -> notFound(QUESTION_NOT_FOUND));
    }

    private static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.NOT_FOUND, detail);
    }
}
