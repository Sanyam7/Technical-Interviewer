package com.techinterviewer.rag.domain;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.rag.DocumentSourceType;
import com.techinterviewer.rag.DocumentStatus;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Visibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A source document of the knowledge base (table {@code knowledge_documents}). Who may retrieve it is decided
 * by its visibility and owner, and the combinations the schema forbids cannot be built: there is one factory per
 * legitimate kind of document, and the constructor is private.
 *
 * <ul>
 *   <li>{@link #curated} and {@link #adminUpload}: PUBLIC, owned by nobody, never a resume;
 *   <li>{@link #resume}: PRIVATE, owned by the candidate, source and type RESUME (a resume is never PUBLIC);
 *   <li>a CURATED document is identified by its source path, which is therefore required.
 * </ul>
 *
 * <p>The ingestion status moves PENDING, PROCESSING, then READY or FAILED; a READY or FAILED document can be processed
 * again (re-index, retry).
 */
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument extends BaseEntity {

    public static final int MAX_SOURCE_PATH_LENGTH = 300;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    @Column(name = "title", nullable = false, length = DocumentContent.MAX_TITLE_LENGTH)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 20)
    private DocumentSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, updatable = false, length = 30)
    private DocumentType docType;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, updatable = false, length = 10)
    private Visibility visibility;

    @Column(name = "owner_user_id", updatable = false)
    private @Nullable UUID ownerUserId;

    @Column(name = "skill_id")
    private @Nullable Long skillId;

    @Column(name = "source_path", length = MAX_SOURCE_PATH_LENGTH)
    private @Nullable String sourcePath;

    @Column(name = "original_filename", length = DocumentContent.MAX_FILENAME_LENGTH)
    private @Nullable String originalFilename;

    @Column(name = "mime_type", nullable = false, length = DocumentContent.MAX_MIME_TYPE_LENGTH)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
    private String contentSha256;

    @Column(name = "extracted_text", nullable = false, columnDefinition = "text")
    private String extractedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentStatus status;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE_LENGTH)
    private @Nullable String errorMessage;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> metadata;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeDocument() {
    }

    private KnowledgeDocument(
            DocumentSourceType sourceType,
            DocumentType docType,
            Visibility visibility,
            @Nullable UUID ownerUserId,
            @Nullable Long skillId,
            @Nullable String sourcePath,
            DocumentContent content,
            Map<String, String> metadata,
            @Nullable UUID createdBy,
            Instant now) {
        this.title = content.title();
        this.sourceType = sourceType;
        this.docType = docType;
        this.visibility = visibility;
        this.ownerUserId = ownerUserId;
        this.skillId = skillId;
        this.sourcePath = sourcePath;
        this.originalFilename = content.originalFilename();
        this.mimeType = content.mimeType();
        this.byteSize = content.byteSize();
        this.contentSha256 = content.contentSha256();
        this.extractedText = content.extractedText();
        this.status = DocumentStatus.PENDING;
        this.chunkCount = 0;
        this.metadata = metadata;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Curated reference material shipped with the application: PUBLIC, no owner, identified by {@code sourcePath}.
     *
     * @param docType REFERENCE, QUESTION_EXEMPLAR or DESIGN_REFERENCE
     */
    public static KnowledgeDocument curated(
            String sourcePath,
            DocumentType docType,
            @Nullable Long skillId,
            DocumentContent content,
            Map<String, String> metadata,
            Instant now) {
        String path = Require.notBlank(sourcePath, "sourcePath", MAX_SOURCE_PATH_LENGTH);
        return create(DocumentSourceType.CURATED, docType, null, skillId, path, content, metadata, null, now);
    }

    /**
     * Material uploaded by an administrator for everyone: PUBLIC, no owner.
     *
     * @param docType REFERENCE, QUESTION_EXEMPLAR or DESIGN_REFERENCE
     */
    public static KnowledgeDocument adminUpload(
            UUID uploadedBy,
            DocumentType docType,
            @Nullable Long skillId,
            DocumentContent content,
            Map<String, String> metadata,
            Instant now) {
        return create(DocumentSourceType.ADMIN_UPLOAD, docType, null, skillId, null, content, metadata,
                Require.nonNull(uploadedBy, "uploadedBy"), now);
    }

    /** A candidate's resume: PRIVATE to {@code ownerUserId}, never public. */
    public static KnowledgeDocument resume(UUID ownerUserId, DocumentContent content, Instant now) {
        Require.nonNull(ownerUserId, "ownerUserId");
        return new KnowledgeDocument(
                DocumentSourceType.RESUME, DocumentType.RESUME, Visibility.PRIVATE, ownerUserId, null, null,
                Require.nonNull(content, "content"), Map.of(), ownerUserId, Require.nonNull(now, "now"));
    }

    private static KnowledgeDocument create(
            DocumentSourceType sourceType,
            DocumentType docType,
            @Nullable UUID ownerUserId,
            @Nullable Long skillId,
            @Nullable String sourcePath,
            DocumentContent content,
            Map<String, String> metadata,
            @Nullable UUID createdBy,
            Instant now) {
        Require.nonNull(docType, "docType");
        if (docType == DocumentType.RESUME) {
            throw new IllegalArgumentException("a RESUME document is private to its owner: use resume()");
        }
        if (skillId != null && skillId <= 0) {
            throw new IllegalArgumentException("skillId must be positive but was " + skillId);
        }
        return new KnowledgeDocument(
                sourceType, docType, Visibility.PUBLIC, ownerUserId, skillId, sourcePath,
                Require.nonNull(content, "content"), Map.copyOf(Require.nonNull(metadata, "metadata")), createdBy,
                Require.nonNull(now, "now"));
    }

    /**
     * Ingestion starts (or starts again for a re-index or a retry).
     *
     * @throws IllegalStateException when the document is PROCESSING already
     */
    public void startProcessing(Instant now) {
        Require.nonNull(now, "now");
        if (status == DocumentStatus.PROCESSING) {
            throw new IllegalStateException("document " + getId() + " is being processed already");
        }
        this.status = DocumentStatus.PROCESSING;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    /**
     * Ingestion succeeded.
     *
     * @param chunkCount the number of chunks now stored for the document, not negative
     * @throws IllegalStateException when the document is not PROCESSING
     */
    public void markReady(int chunkCount, Instant now) {
        requireProcessing();
        int count = Require.atLeast(chunkCount, 0, "chunkCount");
        Require.nonNull(now, "now");
        this.chunkCount = count;
        this.status = DocumentStatus.READY;
        this.updatedAt = now;
    }

    /**
     * Ingestion failed. The caller removes whatever chunks it stored; the chunk count is not touched here.
     *
     * @param message what went wrong, not blank, at most {@value #MAX_ERROR_MESSAGE_LENGTH} characters
     * @throws IllegalStateException when the document is not PROCESSING
     */
    public void markFailed(String message, Instant now) {
        requireProcessing();
        String error = Require.notBlank(message, "errorMessage", MAX_ERROR_MESSAGE_LENGTH);
        Require.nonNull(now, "now");
        this.errorMessage = error;
        this.status = DocumentStatus.FAILED;
        this.updatedAt = now;
    }

    private void requireProcessing() {
        if (status != DocumentStatus.PROCESSING) {
            throw new IllegalStateException("document " + getId() + " is " + status + ", not PROCESSING");
        }
    }

    /** True when {@code userId} may retrieve the document: it is PUBLIC or the user owns it. */
    public boolean isVisibleTo(UUID userId) {
        return visibility == Visibility.PUBLIC || userId.equals(ownerUserId);
    }

    public String getTitle() {
        return title;
    }

    public DocumentSourceType getSourceType() {
        return sourceType;
    }

    public DocumentType getDocType() {
        return docType;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    /** The owner of a PRIVATE document; null for a PUBLIC one. */
    public @Nullable UUID getOwnerUserId() {
        return ownerUserId;
    }

    public @Nullable Long getSkillId() {
        return skillId;
    }

    public @Nullable String getSourcePath() {
        return sourcePath;
    }

    public @Nullable String getOriginalFilename() {
        return originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getByteSize() {
        return byteSize;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public @Nullable String getErrorMessage() {
        return errorMessage;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public @Nullable UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
