package com.techinterviewer.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.ThinkingLevel;
import com.techinterviewer.ai.support.ScriptedLlmProvider;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelRouterTest {

    private static final Duration LEASE = Duration.ofSeconds(90);

    private static AiProperties.Route route(String provider, ThinkingLevel thinking, Duration budget) {
        return new AiProperties.Route(provider, "model-of-" + provider, 0.3, 2048, thinking, budget);
    }

    private static Map<AiTask, AiProperties.Route> allTo(String provider) {
        Map<AiTask, AiProperties.Route> routes = new EnumMap<>(AiTask.class);
        for (AiTask task : AiTask.values()) {
            routes.put(task, route(provider, ThinkingLevel.LOW, Duration.ofSeconds(60)));
        }
        return routes;
    }

    private static AiProperties properties(Map<AiTask, AiProperties.Route> routes) {
        return new AiProperties(true, new AiProperties.Providers(
                new AiProperties.Gemini("key", URI.create("https://g"), Duration.ofSeconds(30), 2),
                new AiProperties.Ollama(true, URI.create("http://o"), Duration.ofSeconds(120), 1, 16384)), routes);
    }

    @Test
    void everyTaskIsResolvedToItsProviderModelAndSettings() {
        LlmProvider gemini = new ScriptedLlmProvider("gemini");
        LlmProvider ollama = new ScriptedLlmProvider("ollama");
        Map<AiTask, AiProperties.Route> routes = allTo("gemini");
        routes.put(AiTask.REPORT_WRITING, route("ollama", ThinkingLevel.OFF, Duration.ofSeconds(200)));

        ModelRouter router = new ModelRouter(properties(routes), List.of(gemini, ollama), LEASE);

        ModelRoute evaluation = router.route(AiTask.ANSWER_EVALUATION);
        assertThat(evaluation.provider()).isSameAs(gemini);
        assertThat(evaluation.model()).isEqualTo("model-of-gemini");
        assertThat(evaluation.options().temperature()).isEqualTo(0.3);
        assertThat(evaluation.callTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(evaluation.maxRetries()).isEqualTo(2);
        ModelRoute report = router.route(AiTask.REPORT_WRITING);
        assertThat(report.provider()).isSameAs(ollama);
        assertThat(report.callTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(report.maxRetries()).isEqualTo(1);
        assertThat(report.options().thinking()).isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    void anEvaluationThatCouldOutliveItsAttemptLeaseIsRefused() {
        Map<AiTask, AiProperties.Route> routes = allTo("gemini");
        routes.put(AiTask.ANSWER_EVALUATION, route("gemini", ThinkingLevel.LOW, LEASE));

        assertThatThrownBy(() -> new ModelRouter(properties(routes), List.of(new ScriptedLlmProvider("gemini")), LEASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("answer-evaluation.budget (PT1M30S) must be shorter than app.evaluation.attempt-lease (PT1M30S)");

        routes.put(AiTask.ANSWER_EVALUATION, route("gemini", ThinkingLevel.LOW, LEASE.minusSeconds(1)));
        assertThat(new ModelRouter(properties(routes), List.of(new ScriptedLlmProvider("gemini")), LEASE)
                .route(AiTask.ANSWER_EVALUATION).budget()).isEqualTo(Duration.ofSeconds(89));
    }

    @Test
    void everyConfigurationProblemIsListedAtOnce() {
        Map<AiTask, AiProperties.Route> routes = allTo("gemini");
        routes.remove(AiTask.FOLLOW_UP_GENERATION);
        routes.put(AiTask.REPORT_WRITING, route("ollama", ThinkingLevel.LOW, Duration.ofSeconds(30)));
        routes.put(AiTask.QUESTION_GENERATION, route("gemini", ThinkingLevel.OFF, Duration.ofSeconds(30)));

        assertThatThrownBy(() -> new ModelRouter(properties(routes), List.of(new ScriptedLlmProvider("gemini")), LEASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.routes.follow-up-generation is missing")
                .hasMessageContaining("app.ai.routes.report-writing.provider is 'ollama', which is not configured (configured: [gemini])")
                .hasMessageContaining("app.ai.routes.question-generation.thinking is OFF, which Gemini 3 models do not support");
    }

    @Test
    void withoutAnyProviderTheMessageSaysHowToConfigureOne() {
        assertThatThrownBy(() -> new ModelRouter(properties(allTo("gemini")), List.of(), LEASE))
                .hasMessageContaining("none; set GEMINI_API_KEY or OLLAMA_ENABLED");
    }

    @Test
    void theApiKeyNeverAppearsInTheProperties() {
        AiProperties.Gemini gemini = new AiProperties.Gemini("AIza-secret", URI.create("https://g"), Duration.ofSeconds(30), 2);

        assertThat(gemini.toString()).doesNotContain("AIza-secret").contains("<redacted>");
        assertThat(new AiProperties.Gemini(" ", URI.create("https://g"), Duration.ofSeconds(30), 2).configured()).isFalse();
    }
}
