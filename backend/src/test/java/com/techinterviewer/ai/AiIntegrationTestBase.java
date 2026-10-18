package com.techinterviewer.ai;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.support.FakeModelServer;
import com.techinterviewer.interview.InterviewApiTestBase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The whole application with the real AI layer and RAG, against one test double of a model behind the real Ollama
 * protocol ({@link FakeModelServer}): it writes schema-valid answers and embeds with a deterministic hashing model.
 * The curated knowledge base is loaded at startup, as in production. Test classes that extend this share one
 * application context and one database; a test that changes the knowledge base restores it.
 */
public abstract class AiIntegrationTestBase extends InterviewApiTestBase {

    protected static final FakeModelServer MODEL = new FakeModelServer(TestJson.applicationMapper());
    protected static final String EMBEDDING_MODEL = "test-embedding";

    @DynamicPropertySource
    static void aiLayer(DynamicPropertyRegistry registry) {
        registry.add("app.ai.enabled", () -> "true");
        registry.add("app.ai.providers.ollama.enabled", () -> "true");
        registry.add("app.ai.providers.ollama.base-url", () -> MODEL.baseUrl().toString());
        for (String task : List.of("question-generation", "follow-up-generation", "answer-evaluation", "report-writing", "resume-extraction")) {
            registry.add("app.ai.routes." + task + ".provider", () -> "ollama");
            registry.add("app.ai.routes." + task + ".model", () -> "fake-model");
        }
        registry.add("app.rag.embedding.provider", () -> "ollama");
        registry.add("app.rag.embedding.model", () -> EMBEDDING_MODEL);
        // The hashing test model knows words, not meaning: texts that share no content word score below about 0.15, but
        // so do some relevant passages, which the keyword half then has to find. RAG_ARCHITECTURE.md explains the choice.
        registry.add("app.rag.retrieval.min-similarity", () -> "0.2");
    }

    @AfterEach
    void embeddingsWorkAgain() {
        MODEL.failEmbeddings(false);
        MODEL.behave(FakeModelServer.Behaviour.HONEST);
        MODEL.recover();
    }
}
