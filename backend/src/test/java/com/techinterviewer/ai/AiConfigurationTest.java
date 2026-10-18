package com.techinterviewer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.techinterviewer.ai.agents.AgentQuestionGenerator;
import com.techinterviewer.ai.agents.AnswerEvaluatorAgent;
import com.techinterviewer.ai.agents.ReportNarratorAgent;
import com.techinterviewer.ai.observability.LlmCallRepository;
import com.techinterviewer.ai.provider.ThinkingLevel;
import com.techinterviewer.ai.routing.AiProperties;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.routing.ModelRouter;
import com.techinterviewer.evaluation.service.EvaluationProperties;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.AnswerEvaluator;
import com.techinterviewer.interview.port.DefaultAiPortsConfiguration;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.port.ReportNarrator;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.techinterviewer.ai.agents.ResumeExtractorAgent;
import com.techinterviewer.resume.DefaultResumeExtractorConfiguration;
import com.techinterviewer.resume.ResumeConfiguration;
import com.techinterviewer.resume.ResumeExtractor;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The AI layer as Spring assembles it from the real application.yml and its profiles: off by default, on with a
 * provider, and refusing to start when it is on but cannot work.
 */
class AiConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiProperties.class, EvaluationProperties.class})
    static class Infrastructure {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        LlmCallRepository llmCallRepository() {
            return mock(LlmCallRepository.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    private static ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + String.join(",", profiles), "APP_CORS_ALLOWED_ORIGINS=https://a.example")
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, ValidationAutoConfiguration.class,
                        DefaultAiPortsConfiguration.class, DefaultResumeExtractorConfiguration.class))
                .withUserConfiguration(Infrastructure.class, ResumeConfiguration.class, AiConfiguration.class);
    }

    @Test
    void offByDefaultTheDefaultPortsAnswerUnavailable() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ModelRouter.class);
            assertThat(context.getBean(AiProperties.class).enabled()).isFalse();
            try {
                context.getBean(AnswerEvaluator.class).evaluate(null);
            } catch (AiUnavailableException expected) {
                return;
            }
            throw new AssertionError("the default evaluator answered");
        });
    }

    @Test
    void onWithAGeminiKeyTheAgentsServeTheThreePorts() {
        runner().withPropertyValues("AI_ENABLED=true", "GEMINI_API_KEY=key-not-used").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(QuestionGenerator.class)).isInstanceOf(AgentQuestionGenerator.class);
            assertThat(context.getBean(AnswerEvaluator.class)).isInstanceOf(AnswerEvaluatorAgent.class);
            assertThat(context.getBean(ReportNarrator.class)).isInstanceOf(ReportNarratorAgent.class);
            assertThat(context.getBean(ResumeExtractor.class)).isInstanceOf(ResumeExtractorAgent.class);
            ModelRouter router = context.getBean(ModelRouter.class);
            assertThat(router.route(AiTask.ANSWER_EVALUATION).model()).isEqualTo("gemini-3.8-flash");
            assertThat(router.route(AiTask.FOLLOW_UP_GENERATION).model()).isEqualTo("gemini-3.5-flash-lite");
            assertThat(router.route(AiTask.ANSWER_EVALUATION).provider().id()).isEqualTo("gemini");
        });
    }

    @Test
    void theRoutesOfApplicationYmlBindToEveryTask() {
        runner().run(context -> {
            AiProperties properties = context.getBean(AiProperties.class);
            assertThat(properties.routes()).containsOnlyKeys(AiTask.values());
            AiProperties.Route evaluation = properties.routes().get(AiTask.ANSWER_EVALUATION);
            assertThat(evaluation.temperature()).isEqualTo(0.1);
            assertThat(evaluation.thinking()).isEqualTo(ThinkingLevel.LOW);
            assertThat(evaluation.budget()).isEqualTo(Duration.ofSeconds(75))
                    .isLessThan(context.getBean(EvaluationProperties.class).attemptLease());
            assertThat(properties.providers().gemini().configured()).isFalse();
        });
    }

    @Test
    void onWithoutAnyProviderTheApplicationRefusesToStart() {
        runner().withPropertyValues("AI_ENABLED=true").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("app.ai.routes.answer-evaluation.provider is 'gemini', which is not configured")
                .hasMessageContaining("set GEMINI_API_KEY or OLLAMA_ENABLED"));
    }

    @Test
    void anEvaluationBudgetLongerThanTheAttemptLeaseStopsTheApplication() {
        runner().withPropertyValues("AI_ENABLED=true", "GEMINI_API_KEY=k", "app.evaluation.attempt-lease=60s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("must be shorter than app.evaluation.attempt-lease"));
    }

    @Test
    void productionTurnsTheLayerOnAndThereforeNeedsAProvider() {
        runner("prod").run(context -> assertThat(context).hasFailed());
        runner("prod").withPropertyValues("GEMINI_API_KEY=k").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AnswerEvaluator.class)).isInstanceOf(AnswerEvaluatorAgent.class);
        });
    }

    @Test
    void theLocalOllamaProfileMovesEveryTaskToTheLocalModelWithinALongerLease() {
        runner("local", "local-ollama").run(context -> {
            assertThat(context).hasNotFailed();
            ModelRouter router = context.getBean(ModelRouter.class);
            for (AiTask task : AiTask.values()) {
                assertThat(router.route(task).provider().id()).isEqualTo("ollama");
                assertThat(router.route(task).model()).isEqualTo("qwen3.5:4b");
                assertThat(router.route(task).options().thinking()).isEqualTo(ThinkingLevel.OFF);
            }
            assertThat(context.getBean(EvaluationProperties.class).attemptLease()).isEqualTo(Duration.ofSeconds(300));
            assertThat(router.route(AiTask.ANSWER_EVALUATION).budget()).isEqualTo(Duration.ofSeconds(260));
        });
    }
}
