package com.techinterviewer.interview.port;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Default beans of the three AI ports for an application in which no AI layer is present. They are not fakes:
 * every call fails with {@link AiUnavailableException} ({@code 503 AI_PROVIDER_UNAVAILABLE}), and the callers
 * degrade as designed (pending evaluations, retryable question generation, a report without narrative).
 *
 * <p>This is an <i>auto-configuration</i> (see {@code META-INF/spring/...AutoConfiguration.imports}) so that
 * {@link ConditionalOnMissingBean} is evaluated after every bean of the application has been registered: an AI
 * layer's implementation always wins, whatever order the configuration classes are processed in.
 */
@AutoConfiguration
public class DefaultAiPortsConfiguration {

    @Bean
    @ConditionalOnMissingBean(QuestionGenerator.class)
    QuestionGenerator unavailableQuestionGenerator() {
        return request -> {
            throw new AiUnavailableException("Question generation is unavailable: no AI provider is configured.");
        };
    }

    @Bean
    @ConditionalOnMissingBean(AnswerEvaluator.class)
    AnswerEvaluator unavailableAnswerEvaluator() {
        return request -> {
            throw new AiUnavailableException("Answer evaluation is unavailable: no AI provider is configured.");
        };
    }

    @Bean
    @ConditionalOnMissingBean(ReportNarrator.class)
    ReportNarrator unavailableReportNarrator() {
        return request -> {
            throw new AiUnavailableException("Report narration is unavailable: no AI provider is configured.");
        };
    }
}
