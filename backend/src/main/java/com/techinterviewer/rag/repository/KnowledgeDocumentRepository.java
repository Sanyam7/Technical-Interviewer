package com.techinterviewer.rag.repository;

import com.techinterviewer.rag.DocumentSourceType;
import com.techinterviewer.rag.domain.KnowledgeDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Access to {@link KnowledgeDocument}. A document is visible to a user when it is PUBLIC or the user owns it; the
 * finders that serve a user apply that rule in the query, so a private document of somebody else is never returned.
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

    /** Access rule: the document {@code id} if it is PUBLIC or owned by {@code userId}; empty otherwise. */
    @Query("""
            select d from KnowledgeDocument d
            where d.id = :id
              and (d.visibility = com.techinterviewer.rag.Visibility.PUBLIC or d.ownerUserId = :userId)""")
    Optional<KnowledgeDocument> findVisibleById(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Access rule: the documents that are PUBLIC or owned by {@code userId}, newest first (ties by id). */
    @Query("""
            select d from KnowledgeDocument d
            where d.visibility = com.techinterviewer.rag.Visibility.PUBLIC or d.ownerUserId = :userId
            order by d.createdAt desc, d.id desc""")
    Page<KnowledgeDocument> findPageVisibleTo(@Param("userId") UUID userId, Pageable pageable);

    /** Access rule: the document {@code id} only if {@code ownerUserId} owns it (a PUBLIC document has no owner and is never returned). */
    Optional<KnowledgeDocument> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    /** Access rule: the document with this content owned by {@code ownerUserId} (duplicate detection of an upload); other owners' documents never match. */
    Optional<KnowledgeDocument> findByOwnerUserIdAndContentSha256(UUID ownerUserId, String contentSha256);

    /** Access rule: the PUBLIC document with this content that has no owner (duplicate detection of curated or admin material). */
    Optional<KnowledgeDocument> findByOwnerUserIdIsNullAndContentSha256(String contentSha256);

    /** Access rule: the document of a given source type at a source path; how the curated corpus loader finds a document to update. Not user scoped: the loader is not a request. */
    Optional<KnowledgeDocument> findBySourceTypeAndSourcePath(DocumentSourceType sourceType, String sourcePath);

    /** Access rule: every document of a source type; how the curated corpus loader finds documents whose file is gone. Not user scoped: the loader is not a request. */
    List<KnowledgeDocument> findBySourceType(DocumentSourceType sourceType);
}
