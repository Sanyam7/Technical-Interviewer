package com.techinterviewer.rag.repository;

import com.techinterviewer.rag.domain.KnowledgeChunk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to {@link KnowledgeChunk}. Visibility is a property of the document; the chunk finders for a user join it. */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {

    /** Access rule: the chunks of {@code documentId} in document order. Not user scoped: the caller took the document from {@code KnowledgeDocumentRepository.findVisibleById} or is an ingestion job. */
    List<KnowledgeChunk> findByDocumentIdOrderByChunkIndexAsc(UUID documentId);

    /** Access rule: the chunks of {@code documentId} in document order, only if the document is PUBLIC or owned by {@code userId}. */
    @Query("""
            select c from KnowledgeChunk c join KnowledgeDocument d on d.id = c.documentId
            where c.documentId = :documentId
              and (d.visibility = com.techinterviewer.rag.Visibility.PUBLIC or d.ownerUserId = :userId)
            order by c.chunkIndex asc""")
    List<KnowledgeChunk> findVisibleByDocumentId(@Param("documentId") UUID documentId, @Param("userId") UUID userId);

    /** Access rule: the chunk {@code id} only if it belongs to {@code documentId}. */
    Optional<KnowledgeChunk> findByIdAndDocumentId(UUID id, UUID documentId);

    long countByDocumentId(UUID documentId);

    /**
     * Removes every chunk of {@code documentId} (a re-chunk replaces them). The database removes the rows that
     * reference the chunks (embeddings, question sources) with them. Must run inside a transaction; the persistence
     * context is cleared so no deleted chunk stays cached.
     *
     * @return the number of chunks removed
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from KnowledgeChunk c where c.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);
}
