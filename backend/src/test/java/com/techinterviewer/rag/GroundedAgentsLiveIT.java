package com.techinterviewer.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.AnswerEvaluator;
import com.techinterviewer.interview.port.EvaluationDigest;
import com.techinterviewer.interview.port.EvaluationRequest;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.NarrativeRequest;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.ReportNarrative;
import com.techinterviewer.interview.port.ReportNarrator;
import com.techinterviewer.interview.port.TopicRef;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The grounded agents on real local models (opt-in, tag {@code live}): Ollama {@code qwen3.5:4b} writes, {@code
 * embeddinggemma} embeds the shipped knowledge base. One question is generated and grounded, a wrong answer to it is
 * evaluated against the material, and a report narrative recommends material for the gap. The model's words vary; the
 * test checks the structure and logs the content for reading.
 *
 * <pre>
 * ./mvnw verify -Dexcluded.test.groups= -Dgroups=live -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GroundedAgentsLiveIT
 * </pre>
 */
@Tag("live")
class GroundedAgentsLiveIT extends InterviewApiTestBase {

    private static final Logger log = LoggerFactory.getLogger(GroundedAgentsLiveIT.class);
    private static final String OLLAMA = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
    private static final String MODEL = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3.5:4b");
    private static final TopicRef TOPIC = new TopicRef("java", "Java", "concurrency", "Concurrency");

    @BeforeAll
    static void theModelsAreAvailable() {
        try (HttpClient http = HttpClient.newHttpClient()) {
            String tags = http.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/tags")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assumeTrue(tags.contains("\"" + MODEL) && tags.contains("\"embeddinggemma"), "Ollama needs " + MODEL + " and embeddinggemma");
        } catch (Exception unreachable) {
            assumeTrue(false, "Ollama is not reachable at " + OLLAMA);
        }
    }

    @DynamicPropertySource
    static void localModels(DynamicPropertyRegistry registry) {
        registry.add("app.ai.enabled", () -> "true");
        registry.add("app.ai.providers.ollama.enabled", () -> "true");
        registry.add("app.ai.providers.ollama.base-url", () -> OLLAMA);
        registry.add("app.ai.providers.ollama.timeout", () -> "300s");
        for (String task : List.of("question-generation", "follow-up-generation", "answer-evaluation", "report-writing", "resume-extraction")) {
            registry.add("app.ai.routes." + task + ".provider", () -> "ollama");
            registry.add("app.ai.routes." + task + ".model", () -> MODEL);
            registry.add("app.ai.routes." + task + ".thinking", () -> "OFF");
            registry.add("app.ai.routes." + task + ".budget", () -> "500s");
        }
        registry.add("app.evaluation.attempt-lease", () -> "600s");
        registry.add("app.rag.embedding.provider", () -> "ollama");
        registry.add("app.rag.embedding.model", () -> "embeddinggemma");
        registry.add("app.rag.embedding.query-timeout", () -> "60s");
        registry.add("app.rag.retrieval.min-similarity", () -> "0.30");
    }

    @Autowired
    QuestionGenerator questions;

    @Autowired
    AnswerEvaluator evaluator;

    @Autowired
    ReportNarrator narrator;

    private String headings(List<UUID> chunkIds) {
        if (chunkIds.isEmpty()) {
            return "[]";
        }
        return chunkIds.stream().map(id -> jdbc.sql("SELECT heading_path FROM knowledge_chunks WHERE id = ?").param(id)
                .query(String.class).single()).collect(Collectors.joining(" | ", "[", "]"));
    }

    @Test
    void aQuestionAnEvaluationAndAReportAreGroundedInTheKnowledgeBase() throws Exception {
        UUID interview = startedInterview(newUser());

        GeneratedQuestion question = questions.generate(new QuestionRequest(interview, Section.QUESTIONING, QuestionKind.TECHNICAL,
                TOPIC, 3, TargetRole.BACKEND, ExperienceLevel.MID, null, List.of("Java"), List.of(), List.of(), null));

        log.info("Question: {}", question.prompt());
        log.info("Grounded in: {}", headings(question.groundingChunkIds()));
        question.expectedKeyPoints().forEach(point -> log.info("  {} {} cites {}", point.id(), point.text(), headings(point.sourceChunkIds())));
        assertThat(question.groundingChunkIds()).as("the question saw material").isNotEmpty();
        assertThat(question.promptVersion()).isEqualTo("v2");

        String wrong = "Declaring the counter volatile makes count++ atomic, so no lock or AtomicInteger is ever needed. "
                + "Volatile also makes the threads take turns, so updates cannot be lost.";
        AnswerAssessment assessment = evaluator.evaluate(new EvaluationRequest(interview, UUID.randomUUID(), Evaluator.TECHNICAL, TOPIC, 3,
                question.prompt(), question.expectedKeyPoints(), null, wrong, null, List.of(), ""));

        log.info("Ratings: {}", assessment.ratings());
        log.info("Summary: {}", assessment.summary());
        for (FactualError error : assessment.factualErrors()) {
            log.info("  error \"{}\" -> \"{}\" backed by {}", error.claim(), error.correction(), headings(error.sourceChunkIds()));
        }
        assertThat(assessment.ratings().get(Criterion.CORRECTNESS).rating()).as("a wrong answer is not rated correct").isLessThanOrEqualTo(2);
        assertThat(assessment.factualErrors()).as("the wrong statement is reported").isNotEmpty();
        List<UUID> evaluationMaterial = jdbc.sql("SELECT rag_chunk_ids FROM llm_calls WHERE id = ?").param(assessment.llmCallId())
                .query((rows, n) -> List.of((UUID[]) rows.getArray(1).getArray())).single();
        log.info("Evaluation material: {}", headings(evaluationMaterial));
        assertThat(evaluationMaterial).as("the evaluator saw material").isNotEmpty();

        List<String> gaps = assessment.keyPoints().stream().filter(point -> point.status() != com.techinterviewer.evaluation.domain.KeyPointStatus.COVERED)
                .map(point -> question.expectedKeyPoints().stream().filter(expected -> expected.id().equals(point.keyPointId()))
                        .findFirst().map(ExpectedKeyPoint::text).orElse(point.keyPointId()))
                .map(text -> "Missed: " + text).limit(EvaluationDigest.MAX_GAPS).toList();
        ReportNarrative narrative = narrator.narrate(new NarrativeRequest(interview, TargetRole.BACKEND, ExperienceLevel.MID,
                InterviewType.TECHNICAL, 30, Recommendation.NOT_YET, List.of(), List.of(new EvaluationDigest(UUID.randomUUID(),
                        Section.QUESTIONING, TOPIC.key(), 3, 30, assessment.summary(), Set.of(), gaps))));

        log.info("Study plan: {}", narrative.studyPlan());
        narrative.furtherReading().forEach(reading -> log.info("  read {} ({})", reading.section(), reading.source()));
        assertThat(narrative.studyPlan()).isNotEmpty();
    }
}
