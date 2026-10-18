package com.techinterviewer.rag.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.domain.KnowledgeChunk;
import com.techinterviewer.rag.domain.KnowledgeDocument;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class KnowledgeChunkRepositoryIT extends PersistenceTestBase {

    private KnowledgeDocument publicDocument(char sha) {
        KnowledgeDocument document = KnowledgeDocument.curated(
                "corpus/" + sha + ".md", DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha(sha)), Map.of(), Fixtures.NOW);
        deleteAfterTest(document);
        inTransaction(() -> documents.saveAndFlush(document));
        return document;
    }

    private KnowledgeDocument resume(UUID owner, char sha) {
        KnowledgeDocument document = KnowledgeDocument.resume(owner, Fixtures.documentContent(Fixtures.sha(sha)), Fixtures.NOW);
        inTransaction(() -> documents.saveAndFlush(document));
        return document;
    }

    private KnowledgeChunk chunk(UUID document, int index) {
        KnowledgeChunk chunk = new KnowledgeChunk(document, index, null, "content " + index, 10, null, null, Map.of(), Fixtures.NOW);
        inTransaction(() -> chunks.saveAndFlush(chunk));
        return chunk;
    }

    @Test
    void aChunkKeepsEveryFieldIncludingItsMetadataDocument() {
        KnowledgeDocument document = publicDocument('a');
        long topic = topicId();
        KnowledgeChunk original = new KnowledgeChunk(
                document.getId(), 4, "Concurrency > Memory model", "volatile guarantees visibility.", 42, topic, 3,
                Map.of("kind", "definition", "lang", "en"), Fixtures.NOW);
        inTransaction(() -> chunks.saveAndFlush(original));

        KnowledgeChunk loaded = inTransaction(() -> chunks.findById(original.getId()).orElseThrow());

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getDocumentId()).isEqualTo(document.getId());
        assertThat(loaded.getChunkIndex()).isEqualTo(4);
        assertThat(loaded.getHeadingPath()).isEqualTo("Concurrency > Memory model");
        assertThat(loaded.getContent()).isEqualTo("volatile guarantees visibility.");
        assertThat(loaded.getTokenCount()).isEqualTo(42);
        assertThat(loaded.getTopicId()).isEqualTo(topic);
        assertThat(loaded.getDifficulty()).isEqualTo(3);
        assertThat(loaded.getMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of("kind", "definition", "lang", "en"));
        assertThat(loaded.getCreatedAt()).isEqualTo(Fixtures.NOW);
        assertThat(jdbc.queryForObject("select metadata ->> 'kind' from knowledge_chunks where id = ?", String.class, original.getId()))
                .isEqualTo("definition");
        assertThat(jdbc.queryForObject("select difficulty from knowledge_chunks where id = ?", Integer.class, original.getId())).isEqualTo(3);
    }

    @Test
    void aMinimalChunkRoundTripsWithNullOptionalColumnsAndEmptyMetadata() {
        KnowledgeDocument document = publicDocument('a');
        KnowledgeChunk original = chunk(document.getId(), 0);

        KnowledgeChunk loaded = inTransaction(() -> chunks.findById(original.getId()).orElseThrow());

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getHeadingPath()).isNull();
        assertThat(loaded.getTopicId()).isNull();
        assertThat(loaded.getDifficulty()).isNull();
        assertThat(loaded.getMetadata()).isEmpty();
    }

    @Test
    void theGeneratedFullTextColumnIsFilledByTheDatabaseForAChunkInsertedThroughTheEntity() {
        KnowledgeDocument document = publicDocument('a');
        KnowledgeChunk original = new KnowledgeChunk(
                document.getId(), 0, "Memory model", "volatile guarantees visibility", 5, null, null, Map.of(), Fixtures.NOW);
        inTransaction(() -> chunks.saveAndFlush(original));

        assertThat(jdbc.queryForObject(
                "select content_tsv @@ to_tsquery('english', 'visibility & memory') from knowledge_chunks where id = ?", Boolean.class, original.getId()))
                .isTrue();
    }

    @Test
    void theChunksOfADocumentComeInDocumentOrder() {
        KnowledgeDocument document = publicDocument('a');
        KnowledgeDocument other = publicDocument('b');
        KnowledgeChunk second = chunk(document.getId(), 2);
        KnowledgeChunk first = chunk(document.getId(), 1);
        KnowledgeChunk zero = chunk(document.getId(), 0);
        chunk(other.getId(), 0);

        assertThat(chunks.findByDocumentIdOrderByChunkIndexAsc(document.getId()))
                .extracting(KnowledgeChunk::getId).containsExactly(zero.getId(), first.getId(), second.getId());
        assertThat(chunks.countByDocumentId(document.getId())).isEqualTo(3);
        assertThat(chunks.countByDocumentId(UuidV7.generate())).isZero();
        assertThat(chunks.findByDocumentIdOrderByChunkIndexAsc(UuidV7.generate())).isEmpty();
    }

    @Test
    void aChunkIsVisibleThroughItsDocumentOnlyToUsersWhoMaySeeTheDocument() {
        UUID owner = newUser();
        UUID stranger = newUser();
        KnowledgeDocument publicDocument = publicDocument('a');
        KnowledgeDocument privateDocument = resume(owner, 'b');
        KnowledgeChunk publicChunk = chunk(publicDocument.getId(), 0);
        KnowledgeChunk privateChunk = chunk(privateDocument.getId(), 0);

        assertThat(chunks.findVisibleByDocumentId(publicDocument.getId(), stranger)).extracting(KnowledgeChunk::getId).containsExactly(publicChunk.getId());
        assertThat(chunks.findVisibleByDocumentId(privateDocument.getId(), owner)).extracting(KnowledgeChunk::getId).containsExactly(privateChunk.getId());
        assertThat(chunks.findVisibleByDocumentId(privateDocument.getId(), stranger)).isEmpty();
        assertThat(chunks.findVisibleByDocumentId(UuidV7.generate(), owner)).isEmpty();
    }

    @Test
    void theVisibleChunksComeInDocumentOrder() {
        UUID owner = newUser();
        KnowledgeDocument document = resume(owner, 'a');
        KnowledgeChunk one = chunk(document.getId(), 1);
        KnowledgeChunk zero = chunk(document.getId(), 0);

        assertThat(chunks.findVisibleByDocumentId(document.getId(), owner)).extracting(KnowledgeChunk::getId).containsExactly(zero.getId(), one.getId());
    }

    @Test
    void aChunkIsFoundOnlyWithItsOwnDocument() {
        KnowledgeDocument document = publicDocument('a');
        KnowledgeDocument other = publicDocument('b');
        KnowledgeChunk chunk = chunk(document.getId(), 0);

        assertThat(chunks.findByIdAndDocumentId(chunk.getId(), document.getId())).isPresent();
        assertThat(chunks.findByIdAndDocumentId(chunk.getId(), other.getId())).isEmpty();
        assertThat(chunks.findByIdAndDocumentId(UuidV7.generate(), document.getId())).isEmpty();
    }

    @Test
    void aDocumentCannotHaveTwoChunksAtTheSameIndexAndAChunkNeedsARealDocument() {
        KnowledgeDocument document = publicDocument('a');
        chunk(document.getId(), 0);

        assertThatThrownBy(() -> chunk(document.getId(), 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> chunk(UuidV7.generate(), 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> chunks.saveAndFlush(
                new KnowledgeChunk(document.getId(), 1, null, "x", 1, Long.MAX_VALUE, null, Map.of(), Fixtures.NOW))))
                .as("unknown topic").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(chunks.countByDocumentId(document.getId())).isOne();
    }

    @Test
    void theDatabaseRejectsADifficultyOutsideOneToFiveAndNegativeIndexOrTokenCount() {
        KnowledgeDocument document = publicDocument('a');
        String insert = "insert into knowledge_chunks (id, document_id, chunk_index, content, token_count, difficulty) values (?, ?, ?, 'x', ?, ?)";

        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), document.getId(), 0, 1, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), document.getId(), 0, 1, 6)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), document.getId(), -1, 1, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), document.getId(), 0, -1, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, UuidV7.generate(), document.getId(), 0, 0, 5)).isOne();
    }

    @Test
    void aReChunkReplacesAllChunksOfOneDocumentOnly() {
        KnowledgeDocument document = publicDocument('a');
        KnowledgeDocument other = publicDocument('b');
        chunk(document.getId(), 0);
        chunk(document.getId(), 1);
        KnowledgeChunk untouched = chunk(other.getId(), 0);

        int removed = inTransaction(() -> chunks.deleteByDocumentId(document.getId()));

        assertThat(removed).isEqualTo(2);
        assertThat(chunks.countByDocumentId(document.getId())).isZero();
        assertThat(chunks.findByDocumentIdOrderByChunkIndexAsc(other.getId())).extracting(KnowledgeChunk::getId).containsExactly(untouched.getId());
    }

    @Test
    void aReChunkInTheSameTransactionDoesNotSeeTheDeletedChunksAgain() {
        KnowledgeDocument document = publicDocument('a');
        KnowledgeChunk old = chunk(document.getId(), 0);

        List<KnowledgeChunk> after = inTransaction(() -> {
            chunks.findById(old.getId()).orElseThrow();
            chunks.deleteByDocumentId(document.getId());
            assertThat(chunks.findById(old.getId())).as("the deleted chunk is gone from the persistence context too").isEmpty();
            chunks.saveAndFlush(new KnowledgeChunk(document.getId(), 0, null, "new text", 2, null, null, Map.of(), Fixtures.NOW));
            return chunks.findByDocumentIdOrderByChunkIndexAsc(document.getId());
        });

        assertThat(after).extracting(KnowledgeChunk::getContent).containsExactly("new text");
    }

    @Test
    void chunksAreDeletedWithTheirDocumentAndWithTheOwnerOfThatDocument() {
        UUID owner = newUser();
        KnowledgeDocument document = resume(owner, 'a');
        KnowledgeChunk viaDocument = chunk(document.getId(), 0);
        KnowledgeDocument second = resume(owner, 'b');
        KnowledgeChunk viaOwner = chunk(second.getId(), 0);

        jdbc.update("delete from knowledge_documents where id = ?", document.getId());
        assertThat(count("knowledge_chunks", "id", viaDocument.getId())).isZero();

        jdbc.update("delete from users where id = ?", owner);
        assertThat(count("knowledge_documents", "id", second.getId())).isZero();
        assertThat(count("knowledge_chunks", "id", viaOwner.getId())).isZero();
    }
}
