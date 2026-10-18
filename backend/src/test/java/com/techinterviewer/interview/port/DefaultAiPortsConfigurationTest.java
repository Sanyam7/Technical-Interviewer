package com.techinterviewer.interview.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class DefaultAiPortsConfigurationTest {

    private static final QuestionRequest QUESTION = new QuestionRequest(UUID.randomUUID(), Section.QUESTIONING, QuestionKind.TECHNICAL,
            new TopicRef("java", "Java", "concurrency", "Concurrency"), 3, TargetRole.BACKEND, ExperienceLevel.MID, null, null, null, null, null);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DefaultAiPortsConfiguration.class));

    @Test
    void withoutAnAiLayerEveryPortAnswersWithAiUnavailable() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(QuestionGenerator.class).hasSingleBean(AnswerEvaluator.class).hasSingleBean(ReportNarrator.class);

            assertThatThrownBy(() -> context.getBean(QuestionGenerator.class).generate(QUESTION))
                    .isInstanceOf(AiUnavailableException.class)
                    .hasMessageContaining("no AI provider is configured");
            assertThatThrownBy(() -> context.getBean(AnswerEvaluator.class).evaluate(null))
                    .isInstanceOf(AiUnavailableException.class);
            assertThatThrownBy(() -> context.getBean(ReportNarrator.class).narrate(null))
                    .isInstanceOf(AiUnavailableException.class);
        });
    }

    @Test
    void theUnavailableExceptionIsAnApiExceptionWith503() {
        AiUnavailableException exception = new AiUnavailableException();

        assertThat(exception.errorCode().status().value()).isEqualTo(503);
        assertThat(exception.errorCode().code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(exception.getMessage()).isEqualTo(AiUnavailableException.DEFAULT_DETAIL);
        assertThat(new AiUnavailableException("detail", new IllegalStateException("provider secret")).getMessage()).isEqualTo("detail");
    }

    @Configuration(proxyBeanMethods = false)
    static class UserGeneratorConfig {
        static final GeneratedQuestion QUESTION_FROM_USER = new GeneratedQuestion("A user supplied question?", List.of(), null,
                QuestionOrigin.EXEMPLAR_FALLBACK, 3, null, 3, List.of(), null, null, null, null);

        @Bean
        QuestionGenerator userGenerator() {
            return request -> QUESTION_FROM_USER;
        }
    }

    @Test
    void aUserSuppliedBeanReplacesTheDefaultOfThatPortOnly() {
        runner.withUserConfiguration(UserGeneratorConfig.class).run(context -> {
            assertThat(context).hasSingleBean(QuestionGenerator.class);
            assertThat(context.getBean(QuestionGenerator.class).generate(QUESTION)).isSameAs(UserGeneratorConfig.QUESTION_FROM_USER);
            // The other two ports still have their defaults.
            assertThatThrownBy(() -> context.getBean(AnswerEvaluator.class).evaluate(null)).isInstanceOf(AiUnavailableException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserEvaluatorConfig {
        @Bean
        AnswerEvaluator userEvaluator() {
            return request -> new AnswerAssessment(Map.of(Criterion.CORRECTNESS, new CriterionRating(4, "")), null, null, null, "ok",
                    null, "p", "1", "m", null);
        }
    }

    @Test
    void theUserBeanWinsEvenWhenItsConfigurationIsRegisteredAfterTheDefaults() {
        // Auto-configurations are evaluated last, so the registration order of the user's classes does not matter.
        runner.withUserConfiguration(UserGeneratorConfig.class, UserEvaluatorConfig.class).run(context -> {
            assertThat(context).hasSingleBean(AnswerEvaluator.class);
            assertThat(context.getBean(AnswerEvaluator.class).evaluate(null).summary()).isEqualTo("ok");
        });
    }
}
