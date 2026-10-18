package com.techinterviewer.ai.agents;

import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.port.QuestionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link QuestionGenerator} port of the interview engine, served by two agents: follow-ups by the
 * {@link FollowUpGeneratorAgent}, every other question by the {@link QuestionGeneratorAgent}.
 *
 * <p>Any failure is {@link AiUnavailableException} with a generic message: the interview keeps the decision and the
 * client retries later ({@code POST /questions/next}). The details are in the log and in {@code llm_calls}.
 */
public final class AgentQuestionGenerator implements QuestionGenerator {

    private static final Logger log = LoggerFactory.getLogger(AgentQuestionGenerator.class);

    private final QuestionGeneratorAgent questions;
    private final FollowUpGeneratorAgent followUps;

    public AgentQuestionGenerator(QuestionGeneratorAgent questions, FollowUpGeneratorAgent followUps) {
        this.questions = questions;
        this.followUps = followUps;
    }

    @Override
    public GeneratedQuestion generate(QuestionRequest request) {
        try {
            return request.kind() == QuestionKind.FOLLOW_UP ? followUps.generate(request) : questions.generate(request);
        } catch (AiUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            log.warn("No {} question could be generated for interview {}: {}", request.kind(), request.interviewId(), failure.getMessage());
            throw new AiUnavailableException(AiUnavailableException.DEFAULT_DETAIL, failure);
        }
    }
}
