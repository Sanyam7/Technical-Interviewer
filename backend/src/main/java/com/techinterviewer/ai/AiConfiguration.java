package com.techinterviewer.ai;

import com.techinterviewer.ai.agents.AgentQuestionGenerator;
import com.techinterviewer.ai.agents.AnswerEvaluatorAgent;
import com.techinterviewer.ai.agents.FollowUpGeneratorAgent;
import com.techinterviewer.ai.agents.QuestionGeneratorAgent;
import com.techinterviewer.ai.agents.ReportNarratorAgent;
import com.techinterviewer.ai.agents.ResumeExtractorAgent;
import com.techinterviewer.ai.observability.LlmCallRecorder;
import com.techinterviewer.ai.observability.LlmCallRepository;
import com.techinterviewer.ai.prompt.PromptRegistry;
import com.techinterviewer.ai.prompt.PromptRenderer;
import com.techinterviewer.ai.prompt.PromptTemplate;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.ai.provider.gemini.GeminiLlmProvider;
import com.techinterviewer.ai.provider.ollama.OllamaLlmProvider;
import com.techinterviewer.ai.routing.AiProperties;
import com.techinterviewer.ai.routing.ModelRouter;
import com.techinterviewer.ai.structured.JsonSchemaRegistry;
import com.techinterviewer.ai.structured.OutputParser;
import com.techinterviewer.ai.structured.Sleeper;
import com.techinterviewer.ai.structured.StructuredOutputClient;
import com.techinterviewer.evaluation.service.EvaluationProperties;
import com.techinterviewer.interview.port.AnswerEvaluator;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.port.ReportNarrator;
import com.techinterviewer.rag.KnowledgeRetriever;
import com.techinterviewer.resume.ResumeExtractor;
import com.techinterviewer.resume.ResumeVocabulary;
import com.techinterviewer.rag.dedup.QuestionDeduplicator;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The AI layer, created only when {@code app.ai.enabled} is true. Its port beans then replace the defaults of
 * {@code DefaultAiPortsConfiguration}, which keep answering "unavailable" when the layer is off.
 *
 * <pre>
 * interview engine ──ports──▶ agents ──▶ StructuredOutputClient ──▶ ModelRouter ──▶ LlmProvider (Gemini | Ollama)
 *                                 │             │  prompts (versioned files), schemas (files), validation, repair
 *                                 │             └─▶ LlmCallRecorder (llm_calls)
 *                                 └─ deterministic guards: evidence, injection, leakage, duplicates
 * </pre>
 *
 * Everything that can be checked at startup is: the routes and providers ({@link ModelRouter}), the prompt files, the
 * schemas each prompt names, and that every prompt an agent uses exists. A misconfigured layer stops the application
 * instead of failing on the first interview.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.ai.enabled")
public class AiConfiguration {

    /** The prompts the agents use; each must exist (its latest version is used). */
    static final Set<String> REQUIRED_PROMPTS = Set.of(
            "technical-question", "coding-question", "system-design-question", "follow-up-question",
            "answer-evaluation-intro", "answer-evaluation-technical", "answer-evaluation-coding",
            "answer-evaluation-system-design", "report-narrative", "resume-extraction", "resume-question");

    /** The connection to a provider must be established quickly; the answer may then take the route's time. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    ModelRouter modelRouter(AiProperties properties, ObjectMapper json, Clock clock, EvaluationProperties evaluation) {
        ProviderHttp http = new ProviderHttp(ProviderHttp.defaultClient(CONNECT_TIMEOUT), json);
        List<LlmProvider> providers = new ArrayList<>();
        AiProperties.Gemini gemini = properties.providers().gemini();
        if (gemini.configured()) {
            providers.add(new GeminiLlmProvider(http, json, gemini.baseUrl(), gemini.apiKey(), clock));
        }
        AiProperties.Ollama ollama = properties.providers().ollama();
        if (ollama.enabled()) {
            providers.add(new OllamaLlmProvider(http, json, ollama.baseUrl(), clock, ollama.contextWindow()));
        }
        return new ModelRouter(properties, providers, evaluation.attemptLease());
    }

    @Bean
    PromptRegistry promptRegistry(JsonSchemaRegistry schemas) {
        PromptRegistry prompts = PromptRegistry.load();
        List<String> problems = new ArrayList<>();
        for (String id : REQUIRED_PROMPTS) {
            try {
                prompts.latest(id);
            } catch (IllegalStateException missing) {
                problems.add(missing.getMessage());
            }
        }
        for (PromptTemplate template : prompts.all()) {
            if (!schemas.contains(template.schema())) {
                problems.add("prompt " + template.id() + "/" + template.version() + " names the unknown schema " + template.schema());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The AI prompts are incomplete:\n - " + String.join("\n - ", problems));
        }
        return prompts;
    }

    @Bean
    JsonSchemaRegistry jsonSchemaRegistry(ObjectMapper json) {
        return JsonSchemaRegistry.load(json);
    }

    @Bean
    LlmCallRecorder llmCallRecorder(LlmCallRepository calls, PlatformTransactionManager transactionManager, Clock clock) {
        return new LlmCallRecorder(calls, new TransactionTemplate(transactionManager), clock);
    }

    @Bean
    StructuredOutputClient structuredOutputClient(
            ModelRouter router, ObjectMapper json, Validator validator, LlmCallRecorder recorder, Clock clock) {
        return new StructuredOutputClient(router, new PromptRenderer(), new OutputParser(json, validator), recorder, clock, Sleeper.THREAD);
    }

    /**
     * The knowledge base, when RAG is on ({@code RagConfiguration}); otherwise every agent works ungrounded and its
     * calls record no chunk list.
     */
    private static KnowledgeRetriever knowledge(ObjectProvider<KnowledgeRetriever> retriever) {
        return retriever.getIfAvailable(() -> KnowledgeRetriever.NONE);
    }

    /** Semantic de-duplication of questions, when RAG (and so an embedding model) is on; otherwise only the lexical check. */
    private static QuestionDeduplicator deduplicator(ObjectProvider<QuestionDeduplicator> deduplicator) {
        return deduplicator.getIfAvailable(() -> QuestionDeduplicator.NONE);
    }

    @Bean
    QuestionGenerator aiQuestionGenerator(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas,
            ObjectProvider<KnowledgeRetriever> retriever, ObjectProvider<QuestionDeduplicator> deduplicator, ResumeVocabulary vocabulary) {
        return new AgentQuestionGenerator(
                new QuestionGeneratorAgent(client, prompts, schemas, knowledge(retriever), deduplicator(deduplicator), vocabulary),
                new FollowUpGeneratorAgent(client, prompts, schemas, knowledge(retriever), deduplicator(deduplicator)));
    }

    @Bean
    AnswerEvaluator aiAnswerEvaluator(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas,
            ObjectProvider<KnowledgeRetriever> retriever) {
        return new AnswerEvaluatorAgent(client, prompts, schemas, knowledge(retriever));
    }

    @Bean
    ResumeExtractor aiResumeExtractor(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas) {
        return new ResumeExtractorAgent(client, prompts, schemas);
    }

    @Bean
    ReportNarrator aiReportNarrator(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas,
            ObjectProvider<KnowledgeRetriever> retriever) {
        return new ReportNarratorAgent(client, prompts, schemas, knowledge(retriever));
    }
}
