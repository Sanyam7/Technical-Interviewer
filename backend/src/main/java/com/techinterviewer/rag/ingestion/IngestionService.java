package com.techinterviewer.rag.ingestion;

import com.techinterviewer.rag.DocumentSourceType;
import com.techinterviewer.rag.chunking.Chunk;
import com.techinterviewer.rag.chunking.MarkdownChunker;
import com.techinterviewer.rag.corpus.CorpusDocument;
import com.techinterviewer.rag.domain.DocumentContent;
import com.techinterviewer.rag.domain.KnowledgeChunk;
import com.techinterviewer.rag.domain.KnowledgeDocument;
import com.techinterviewer.rag.repository.KnowledgeChunkRepository;
import com.techinterviewer.rag.repository.KnowledgeDocumentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores curated documents and their chunks, one document per transaction. A document is identified by its source
 * path: unchanged content (same hash) is left alone, changed content replaces the document and its chunks (their
 * vectors and question links go with them through the foreign keys), and a new path creates a document. Chunks are
 * searchable by keyword as soon as they are stored; their vectors are added by {@link ChunkEmbeddings}.
 */
public final class IngestionService {

    public enum Outcome { CREATED, UPDATED, UNCHANGED }

    static final String MIME_TYPE = "text/markdown";

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final MarkdownChunker chunker;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public IngestionService(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks, MarkdownChunker chunker,
            TransactionTemplate transaction, Clock clock) {
        this.documents = documents;
        this.chunks = chunks;
        this.chunker = chunker;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Creates, replaces or keeps the curated document at {@code document.sourcePath()}.
     *
     * @param skillId the catalog skill the front matter names, resolved by the caller
     * @param topicId the catalog topic the front matter names, resolved by the caller
     */
    public Outcome upsertCurated(CorpusDocument document, @Nullable Long skillId, @Nullable Long topicId) {
        return transaction.execute(status -> {
            Optional<KnowledgeDocument> existing = documents.findBySourceTypeAndSourcePath(DocumentSourceType.CURATED, document.sourcePath());
            if (existing.isPresent() && existing.get().getContentSha256().equals(document.contentSha256())
                    && existing.get().getStatus() == com.techinterviewer.rag.DocumentStatus.READY) {
                return Outcome.UNCHANGED;
            }
            existing.ifPresent(old -> {
                documents.delete(old);
                documents.flush();
            });
            store(document, skillId, topicId, clock.instant());
            return existing.isPresent() ? Outcome.UPDATED : Outcome.CREATED;
        });
    }

    /** Removes the curated documents whose file is no longer in the knowledge base. */
    public int removeCuratedExcept(Set<String> sourcePaths) {
        return transaction.execute(status -> {
            List<KnowledgeDocument> gone = documents.findBySourceType(DocumentSourceType.CURATED).stream()
                    .filter(document -> !sourcePaths.contains(document.getSourcePath()))
                    .toList();
            documents.deleteAll(gone);
            return gone.size();
        });
    }

    private void store(CorpusDocument source, @Nullable Long skillId, @Nullable Long topicId, Instant now) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("technology", source.technology().slug());
        if (source.topicKey() != null) {
            metadata.put("topic", source.topicKey());
        }
        KnowledgeDocument document = documents.save(KnowledgeDocument.curated(source.sourcePath(), source.docType(), skillId,
                new DocumentContent(source.title(), null, MIME_TYPE, source.byteSize(), source.contentSha256(), source.body()),
                metadata, now));
        document.startProcessing(now);
        List<Chunk> pieces = chunker.chunk(source.title(), source.body());
        for (Chunk piece : pieces) {
            chunks.save(new KnowledgeChunk(document.getId(), piece.index(), piece.headingPath(), piece.content(), piece.tokenCount(),
                    topicId, source.difficulty(), metadata, now));
        }
        if (pieces.isEmpty()) {
            document.markFailed("the document has no text to index", now);
        } else {
            document.markReady(pieces.size(), now);
        }
    }
}
