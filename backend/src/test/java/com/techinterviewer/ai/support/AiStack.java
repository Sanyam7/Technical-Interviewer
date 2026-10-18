package com.techinterviewer.ai.support;

import com.techinterviewer.TestClock;
import com.techinterviewer.TestJson;
import com.techinterviewer.ai.agents.AgentQuestionGenerator;
import com.techinterviewer.ai.agents.AnswerEvaluatorAgent;
import com.techinterviewer.ai.agents.FollowUpGeneratorAgent;
import com.techinterviewer.ai.agents.QuestionGeneratorAgent;
import com.techinterviewer.ai.agents.ReportNarratorAgent;
import com.techinterviewer.ai.agents.ResumeExtractorAgent;
import com.techinterviewer.ai.prompt.PromptRegistry;
import com.techinterviewer.ai.prompt.PromptRenderer;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.ThinkingLevel;
import com.techinterviewer.ai.routing.AiProperties;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.routing.ModelRouter;
import com.techinterviewer.ai.structured.JsonSchemaRegistry;
import com.techinterviewer.ai.structured.OutputParser;
import com.techinterviewer.ai.structured.StructuredOutputClient;
import com.techinterviewer.rag.KnowledgeRetriever;
import com.techinterviewer.rag.dedup.QuestionDeduplicator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The real AI layer (router, prompts, schemas, renderer, parser, structured client, agents) around a given provider,
 * without Spring or a database: calls are captured in memory and waits advance the test clock instead of sleeping.
 */
public final class AiStack {

    public static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    public static final Duration ATTEMPT_LEASE = Duration.ofSeconds(90);

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final PromptRegistry PROMPTS = PromptRegistry.load();
    private static final JsonSchemaRegistry SCHEMAS = JsonSchemaRegistry.load(TestJson.applicationMapper());

    public final TestClock clock = new TestClock(NOW);
    public final CapturingCallRecorder recorder = new CapturingCallRecorder();
    public final List<Duration> sleeps = new ArrayList<>();
    public final StructuredOutputClient client;
    public final ModelRouter router;
    /** The knowledge base the agents ground their tasks in; none by default, replace it to test grounding. */
    public KnowledgeRetriever knowledge = KnowledgeRetriever.NONE;
    public QuestionDeduplicator deduplicator = QuestionDeduplicator.NONE;

    private AiStack(LlmProvider provider, Duration callTimeout, int maxRetries, Duration budget, String model, ThinkingLevel thinking) {
        Map<AiTask, AiProperties.Route> routes = new EnumMap<>(AiTask.class);
        for (AiTask task : AiTask.values()) {
            routes.put(task, new AiProperties.Route(provider.id(), model, 0.2, 4096, thinking, budget));
        }
        AiProperties properties = new AiProperties(true,
                new AiProperties.Providers(
                        new AiProperties.Gemini("test-key", URI.create("http://localhost"), callTimeout, maxRetries),
                        new AiProperties.Ollama(true, URI.create("http://localhost"), callTimeout, maxRetries, 16384)),
                routes);
        // A slow live model gets a lease to match its budget, as the local-ollama profile does.
        Duration lease = budget.compareTo(ATTEMPT_LEASE) < 0 ? ATTEMPT_LEASE : budget.plusMinutes(1);
        this.router = new ModelRouter(properties, List.of(provider), lease);
        this.client = new StructuredOutputClient(
                router, new PromptRenderer(), new OutputParser(TestJson.applicationMapper(), VALIDATOR), recorder, clock,
                duration -> {
                    sleeps.add(duration);
                    clock.advance(duration);
                });
    }

    /** A stack with a 30 s call timeout, 2 retries and a 60 s budget for every task. */
    public static AiStack around(LlmProvider provider) {
        return new AiStack(provider, Duration.ofSeconds(30), 2, Duration.ofSeconds(60), "test-model", ThinkingLevel.LOW);
    }

    public static AiStack around(LlmProvider provider, Duration callTimeout, int maxRetries, Duration budget) {
        return new AiStack(provider, callTimeout, maxRetries, budget, "test-model", ThinkingLevel.LOW);
    }

    /** For the live smoke test: a real provider and model. */
    public static AiStack live(LlmProvider provider, String model, Duration budget) {
        return live(provider, model, budget, ThinkingLevel.OFF);
    }

    public static AiStack live(LlmProvider provider, String model, Duration budget, ThinkingLevel thinking) {
        return new AiStack(provider, budget, 1, budget, model, thinking);
    }

    public static PromptRegistry prompts() {
        return PROMPTS;
    }

    public static JsonSchemaRegistry schemas() {
        return SCHEMAS;
    }

    public static Validator validator() {
        return VALIDATOR;
    }

    public AgentQuestionGenerator questionGenerator() {
        return new AgentQuestionGenerator(
                new QuestionGeneratorAgent(client, PROMPTS, SCHEMAS, knowledge, deduplicator),
                new FollowUpGeneratorAgent(client, PROMPTS, SCHEMAS, knowledge, deduplicator));
    }

    public AnswerEvaluatorAgent answerEvaluator() {
        return new AnswerEvaluatorAgent(client, PROMPTS, SCHEMAS, knowledge);
    }

    public ResumeExtractorAgent resumeExtractor() {
        return new ResumeExtractorAgent(client, PROMPTS, SCHEMAS);
    }

    public ReportNarratorAgent reportNarrator() {
        return new ReportNarratorAgent(client, PROMPTS, SCHEMAS, knowledge);
    }
}
