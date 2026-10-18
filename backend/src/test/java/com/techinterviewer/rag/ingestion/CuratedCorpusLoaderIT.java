package com.techinterviewer.rag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.ai.AiIntegrationTestBase;
import com.techinterviewer.rag.Technology;
import com.techinterviewer.rag.retrieval.ChunkSearch;
import com.techinterviewer.rag.retrieval.RetrievalQuery;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Loading the curated knowledge base for real (PostgreSQL + pgvector): the startup load, idempotency, an edited, a
 * removed and a malformed document, and an embedding outage. Each test restores the shipped corpus afterwards.
 */
class CuratedCorpusLoaderIT extends AiIntegrationTestBase {

    private static final String EDITED = "java/concurrency.md";

    @Autowired
    CuratedCorpusLoader loader;

    @Autowired
    ChunkEmbeddings embeddings;

    @Autowired
    ChunkSearch search;

    @TempDir
    Path temp;

    private final List<Resource> shipped = CuratedCorpusLoader.resources(CuratedCorpusLoader.LOCATION);

    @AfterEach
    void restoreTheShippedCorpus() {
        MODEL.failEmbeddings(false);
        loader.load();
    }

    private long curatedDocuments() {
        return jdbc.sql("SELECT count(*) FROM knowledge_documents WHERE source_type = 'CURATED'").query(Long.class).single();
    }

    private List<UUID> chunksOf(String path) {
        return jdbc.sql("""
                SELECT c.id FROM knowledge_chunks c JOIN knowledge_documents d ON d.id = c.document_id
                WHERE d.source_type = 'CURATED' AND d.source_path = ? ORDER BY c.chunk_index""").param(path).query(UUID.class).list();
    }

    /** A copy of the shipped corpus in which one file is replaced or added, under a knowledge-base folder. */
    private List<Resource> corpusWith(String path, String content) throws IOException {
        Path file = temp.resolve("knowledge-base").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        List<Resource> files = new ArrayList<>(shipped.stream().filter(resource -> !CuratedCorpusLoader.sourcePath(resource).equals(path)).toList());
        files.add(new FileSystemResource(file));
        return files;
    }

    private String shippedText(String path) throws IOException {
        Resource resource = shipped.stream().filter(file -> CuratedCorpusLoader.sourcePath(file).equals(path)).findFirst().orElseThrow();
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void atStartupEveryShippedDocumentIsStoredChunkedAndEmbedded() {
        assertThat(curatedDocuments()).isEqualTo(shipped.size());
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_documents WHERE source_type = 'CURATED' AND status <> 'READY'")
                .query(Long.class).single()).isZero();
        long chunks = jdbc.sql("SELECT count(*) FROM knowledge_chunks").query(Long.class).single();
        assertThat(chunks).isGreaterThan(200);
        assertThat(jdbc.sql("SELECT count(*) FROM embeddings WHERE model = ?").param(EMBEDDING_MODEL).query(Long.class).single())
                .isEqualTo(chunks);
        assertThat(embeddings.countMissing(EMBEDDING_MODEL)).isZero();
        assertThat(jdbc.sql("SELECT DISTINCT metadata ->> 'technology' FROM knowledge_documents").query(String.class).set())
                .hasSize(Technology.values().length);
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_chunks WHERE topic_id IS NOT NULL").query(Long.class).single())
                .as("chunks of catalog topics carry the topic").isGreaterThan(100);
    }

    @Test
    void aSecondLoadChangesNothingAndCallsNoModel() {
        int calls = MODEL.embeddingCalls();
        List<UUID> before = chunksOf(EDITED);

        CuratedCorpusLoader.Report report = loader.load();

        assertThat(report.unchanged()).isEqualTo(shipped.size());
        assertThat(report.created() + report.updated() + report.removed() + report.embedded()).isZero();
        assertThat(report.malformed()).isEmpty();
        assertThat(MODEL.embeddingCalls()).isEqualTo(calls);
        assertThat(chunksOf(EDITED)).isEqualTo(before);
    }

    @Test
    void anEditedDocumentReplacesOnlyItsOwnChunksAndOnlyThoseAreEmbedded() throws IOException {
        List<UUID> before = chunksOf(EDITED);
        String edited = shippedText(EDITED) + "\n## Structured concurrency\n\nA scope ties the lifetime of subtasks to a block of code.\n";

        CuratedCorpusLoader.Report report = loader.load(corpusWith(EDITED, edited));

        assertThat(report.updated()).isOne();
        assertThat(report.unchanged()).isEqualTo(shipped.size() - 1);
        List<UUID> after = chunksOf(EDITED);
        assertThat(after).hasSize(before.size() + 1).doesNotContainAnyElementsOf(before);
        assertThat(report.embedded()).isEqualTo(after.size());
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_documents WHERE source_path = ?").param(EDITED).query(Long.class).single())
                .as("one document per path").isOne();
    }

    @Test
    void aDocumentWhoseFileIsGoneIsRemovedButAnEmptyCorpusNeverWipesTheKnowledgeBase() {
        List<Resource> withoutOne = shipped.stream().filter(file -> !CuratedCorpusLoader.sourcePath(file).equals(EDITED)).toList();

        assertThat(loader.load(withoutOne).removed()).isOne();
        assertThat(chunksOf(EDITED)).isEmpty();

        assertThat(loader.load(List.of()).removed()).isZero();
        assertThat(curatedDocuments()).isEqualTo(shipped.size() - 1);
    }

    @Test
    void aMalformedDocumentOrOneNamingAnUnknownTopicIsSkippedAndTheRestIsLoaded() throws IOException {
        List<Resource> files = new ArrayList<>(corpusWith("java/no-front-matter.md", "## Just text\n\nNo front matter here.\n"));
        Path unknown = temp.resolve("knowledge-base/java/unknown-topic.md");
        Files.writeString(unknown, "---\ntitle: Kotlin\ntechnology: java\nskill: java\ntopic: coroutines\ndoc_type: REFERENCE\n---\n## Body\n\nText.\n");
        files.add(new FileSystemResource(unknown));

        CuratedCorpusLoader.Report report = loader.load(files);

        assertThat(report.malformed()).containsExactlyInAnyOrder("java/no-front-matter.md", "java/unknown-topic.md");
        assertThat(report.unchanged()).isEqualTo(shipped.size());
        assertThat(jdbc.sql("SELECT count(*) FROM knowledge_documents WHERE source_path LIKE 'java/%-%topic%.md' OR source_path = 'java/no-front-matter.md'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void whenTheEmbeddingModelIsDownChunksAreStoredFoundByKeywordAndEmbeddedAtTheNextRun() throws IOException {
        MODEL.failEmbeddings(true);
        String edited = shippedText(EDITED) + "\n## Zwiebelfisch scheduling\n\nThe zwiebelfisch scheduler is a made-up word for this test.\n";

        CuratedCorpusLoader.Report outage = loader.load(corpusWith(EDITED, edited));

        assertThat(outage.updated()).isOne();
        assertThat(outage.embedded()).isZero();
        assertThat(outage.stillMissing()).isEqualTo(chunksOf(EDITED).size());
        RetrievalQuery query = new RetrievalQuery("zwiebelfisch scheduler", List.of("zwiebelfisch"), Set.of(Technology.JAVA), Set.of(),
                null, null, null, 5);
        assertThat(search.keyword(query.keywordTerms(), query, 5)).as("searchable by keyword at once").hasSize(1);

        MODEL.failEmbeddings(false);
        assertThat(loader.embedMissing()).isEqualTo(chunksOf(EDITED).size());
        assertThat(embeddings.countMissing(EMBEDDING_MODEL)).isZero();
    }
}
