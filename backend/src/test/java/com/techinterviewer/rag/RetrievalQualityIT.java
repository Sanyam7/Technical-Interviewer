package com.techinterviewer.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.ai.AiIntegrationTestBase;
import com.techinterviewer.rag.embedding.EmbeddingService;
import com.techinterviewer.rag.retrieval.ChunkSearch;
import com.techinterviewer.rag.retrieval.HybridRetriever;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The retrieval evaluation set, in every build, over the shipped knowledge base with the deterministic hashing test
 * embedding. The hashing model knows words, not meaning, so this measures the pipeline (chunking, filters, keyword
 * search, fusion, thresholds), not semantic quality, and guards it against regressions. The thresholds are the measured
 * baseline rounded down; RAG_ARCHITECTURE.md records the numbers, and the live run with a real model.
 */
class RetrievalQualityIT extends AiIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(RetrievalQualityIT.class);

    @Autowired
    ChunkSearch search;

    @Autowired
    EmbeddingService embeddings;

    @Autowired
    RagProperties rag;

    @Test
    void theLabelledSetIsRetrievedAtLeastAsWellAsTheRecordedBaseline() {
        List<RetrievalEvaluation.Case> cases = RetrievalEvaluation.load();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(60);

        RetrievalEvaluation.Report report = RetrievalEvaluation.run("hashing-test-embedding", cases,
                new HybridRetriever(search, embeddings, rag.retrieval()));
        log.info("{} (report: {})", report.summary(), RetrievalEvaluation.write(report));
        report.cases().stream().filter(result -> result.relevantTotal() > 0 && result.firstRelevantRank() == 0)
                .forEach(miss -> log.info("missed {}: got {}", miss.id(), miss.retrieved()));

        assertThat(report.recallAt5()).as("recall@5").isGreaterThanOrEqualTo(BASELINE_RECALL);
        assertThat(report.mrrAt5()).as("MRR@5").isGreaterThanOrEqualTo(BASELINE_MRR);
        assertThat(report.abstention()).as("abstention on unanswerable queries").isGreaterThanOrEqualTo(BASELINE_ABSTENTION);
    }

    static final double BASELINE_RECALL = 0.80;
    static final double BASELINE_MRR = 0.75;
    static final double BASELINE_ABSTENTION = 1.0;
}
