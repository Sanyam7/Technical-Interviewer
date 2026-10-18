package com.techinterviewer.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.rag.DocumentSourceType;
import com.techinterviewer.rag.DocumentStatus;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Visibility;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Visibility, ownership and status rules of KnowledgeDocument, without a database. */
class KnowledgeDocumentTest {

    private static final UUID OWNER = UuidV7.generate();
    private static final UUID ADMIN = UuidV7.generate();
    private static final Instant T1 = Fixtures.NOW.plusSeconds(5);
    private static final DocumentContent CONTENT = Fixtures.documentContent(Fixtures.sha('a'));

    private static KnowledgeDocument curated() {
        return KnowledgeDocument.curated("java/concurrency.md", DocumentType.REFERENCE, 4L, CONTENT, Map.of("lang", "en"), Fixtures.NOW);
    }

    // ---- who may see what ----------------------------------------------------------------------------------------

    @Test
    void curatedMaterialIsPublicWithoutOwnerAndIdentifiedByItsPath() {
        KnowledgeDocument document = curated();

        assertThat(document.getSourceType()).isEqualTo(DocumentSourceType.CURATED);
        assertThat(document.getDocType()).isEqualTo(DocumentType.REFERENCE);
        assertThat(document.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(document.getOwnerUserId()).isNull();
        assertThat(document.getSourcePath()).isEqualTo("java/concurrency.md");
        assertThat(document.getSkillId()).isEqualTo(4L);
        assertThat(document.getCreatedBy()).isNull();
        assertThat(document.getMetadata()).containsExactly(Map.entry("lang", "en"));
    }

    @Test
    void anAdminUploadIsPublicWithoutOwnerAndRemembersWhoUploadedIt() {
        KnowledgeDocument document = KnowledgeDocument.adminUpload(ADMIN, DocumentType.QUESTION_EXEMPLAR, null, CONTENT, Map.of(), Fixtures.NOW);

        assertThat(document.getSourceType()).isEqualTo(DocumentSourceType.ADMIN_UPLOAD);
        assertThat(document.getDocType()).isEqualTo(DocumentType.QUESTION_EXEMPLAR);
        assertThat(document.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(document.getOwnerUserId()).isNull();
        assertThat(document.getCreatedBy()).isEqualTo(ADMIN);
        assertThat(document.getSourcePath()).isNull();
        assertThat(document.getSkillId()).isNull();
    }

    @Test
    void aResumeIsPrivateToItsOwnerAndNeverPublic() {
        KnowledgeDocument document = KnowledgeDocument.resume(OWNER, CONTENT, Fixtures.NOW);

        assertThat(document.getSourceType()).isEqualTo(DocumentSourceType.RESUME);
        assertThat(document.getDocType()).isEqualTo(DocumentType.RESUME);
        assertThat(document.getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(document.getOwnerUserId()).isEqualTo(OWNER);
        assertThat(document.getCreatedBy()).isEqualTo(OWNER);
        assertThat(document.getMetadata()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, names = "RESUME", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherTypeIsAvailableForPublicMaterial(DocumentType type) {
        assertThat(KnowledgeDocument.curated("p.md", type, null, CONTENT, Map.of(), Fixtures.NOW).getDocType()).isEqualTo(type);
        assertThat(KnowledgeDocument.adminUpload(ADMIN, type, null, CONTENT, Map.of(), Fixtures.NOW).getDocType()).isEqualTo(type);
    }

    @Test
    void publicMaterialCanNeverBeAResume() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KnowledgeDocument.curated("p.md", DocumentType.RESUME, null, CONTENT, Map.of(), Fixtures.NOW))
                .withMessageContaining("resume()");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KnowledgeDocument.adminUpload(ADMIN, DocumentType.RESUME, null, CONTENT, Map.of(), Fixtures.NOW))
                .withMessageContaining("resume()");
    }

    @Test
    void publicDocumentsAreVisibleToEveryoneAndPrivateOnesOnlyToTheirOwner() {
        UUID stranger = UuidV7.generate();
        KnowledgeDocument resume = KnowledgeDocument.resume(OWNER, CONTENT, Fixtures.NOW);

        assertThat(curated().isVisibleTo(stranger)).isTrue();
        assertThat(resume.isVisibleTo(OWNER)).isTrue();
        assertThat(resume.isVisibleTo(stranger)).isFalse();
    }

    // ---- creation rules -------------------------------------------------------------------------------------------

    @Test
    void aCuratedDocumentNeedsASourcePath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KnowledgeDocument.curated(null, DocumentType.REFERENCE, null, CONTENT, Map.of(), Fixtures.NOW))
                .withMessageContaining("sourcePath");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KnowledgeDocument.curated(" ", DocumentType.REFERENCE, null, CONTENT, Map.of(), Fixtures.NOW));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KnowledgeDocument.curated("p".repeat(301), DocumentType.REFERENCE, null, CONTENT, Map.of(), Fixtures.NOW));
        assertThat(KnowledgeDocument.curated("p".repeat(300), DocumentType.REFERENCE, null, CONTENT, Map.of(), Fixtures.NOW).getSourcePath()).hasSize(300);
    }

    @Test
    void aNewDocumentIsPendingWithoutChunksAndKeepsItsContent() {
        KnowledgeDocument document = curated();

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(document.getChunkCount()).isZero();
        assertThat(document.getErrorMessage()).isNull();
        assertThat(document.getTitle()).isEqualTo(CONTENT.title());
        assertThat(document.getOriginalFilename()).isEqualTo(CONTENT.originalFilename());
        assertThat(document.getMimeType()).isEqualTo(CONTENT.mimeType());
        assertThat(document.getByteSize()).isEqualTo(CONTENT.byteSize());
        assertThat(document.getContentSha256()).isEqualTo(CONTENT.contentSha256());
        assertThat(document.getExtractedText()).isEqualTo(CONTENT.extractedText());
        assertThat(document.getCreatedAt()).isEqualTo(Fixtures.NOW);
        assertThat(document.getUpdatedAt()).isEqualTo(Fixtures.NOW);
        assertThat(UuidV7.isV7(document.getId())).isTrue();
    }

    @Test
    void requiredValuesAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.curated("p", null, null, CONTENT, Map.of(), Fixtures.NOW)).withMessageContaining("docType");
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.curated("p", DocumentType.REFERENCE, null, null, Map.of(), Fixtures.NOW)).withMessageContaining("content");
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.curated("p", DocumentType.REFERENCE, null, CONTENT, null, Fixtures.NOW)).withMessageContaining("metadata");
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.curated("p", DocumentType.REFERENCE, null, CONTENT, Map.of(), null)).withMessageContaining("now");
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.adminUpload(null, DocumentType.REFERENCE, null, CONTENT, Map.of(), Fixtures.NOW)).withMessageContaining("uploadedBy");
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.resume(null, CONTENT, Fixtures.NOW)).withMessageContaining("ownerUserId");
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.resume(OWNER, null, Fixtures.NOW)).withMessageContaining("content");
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.resume(OWNER, CONTENT, null)).withMessageContaining("now");
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocument.curated("p", DocumentType.REFERENCE, 0L, CONTENT, Map.of(), Fixtures.NOW)).withMessageContaining("skillId");
    }

    @Test
    void metadataIsCopiedAndCannotBeModified() {
        Map<String, String> given = new HashMap<>(Map.of("a", "1"));
        KnowledgeDocument document = KnowledgeDocument.curated("p", DocumentType.REFERENCE, null, CONTENT, given, Fixtures.NOW);

        given.put("b", "2");

        assertThat(document.getMetadata()).containsOnlyKeys("a");
        assertThatThrownBy(() -> document.getMetadata().put("c", "3")).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- status ---------------------------------------------------------------------------------------------------

    @Test
    void ingestionMovesFromPendingThroughProcessingToReady() {
        KnowledgeDocument document = curated();

        document.startProcessing(T1);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(document.getUpdatedAt()).isEqualTo(T1);

        document.markReady(12, T1.plusSeconds(1));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(document.getChunkCount()).isEqualTo(12);
        assertThat(document.getErrorMessage()).isNull();
        assertThat(document.getUpdatedAt()).isEqualTo(T1.plusSeconds(1));
    }

    @Test
    void aFailedIngestionKeepsTheMessageAndCanBeRetriedAndReindexed() {
        KnowledgeDocument document = curated();
        document.startProcessing(T1);

        document.markFailed("embedding model unavailable", T1.plusSeconds(2));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getErrorMessage()).isEqualTo("embedding model unavailable");
        assertThat(document.getUpdatedAt()).isEqualTo(T1.plusSeconds(2));

        document.startProcessing(T1.plusSeconds(3));
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(document.getErrorMessage()).as("a retry clears the old error").isNull();

        document.markReady(3, T1.plusSeconds(4));
        document.startProcessing(T1.plusSeconds(5));
        assertThat(document.getStatus()).as("a READY document can be re-indexed").isEqualTo(DocumentStatus.PROCESSING);
        assertThat(document.getChunkCount()).as("until the re-index is done the old count stands").isEqualTo(3);
    }

    @Test
    void aDocumentBeingProcessedCannotBeProcessedAgain() {
        KnowledgeDocument document = curated();
        document.startProcessing(T1);

        assertThatIllegalStateException().isThrownBy(() -> document.startProcessing(T1)).withMessageContaining("processed already");
    }

    @Test
    void readyAndFailedRequireProcessing() {
        KnowledgeDocument pending = curated();
        assertThatIllegalStateException().isThrownBy(() -> pending.markReady(1, T1)).withMessageContaining("PENDING");
        assertThatIllegalStateException().isThrownBy(() -> pending.markFailed("x", T1)).withMessageContaining("PENDING");

        KnowledgeDocument ready = curated();
        ready.startProcessing(T1);
        ready.markReady(1, T1);
        assertThatIllegalStateException().isThrownBy(() -> ready.markReady(2, T1));
        assertThatIllegalStateException().isThrownBy(() -> ready.markFailed("x", T1));
        assertThat(ready.getChunkCount()).isEqualTo(1);
    }

    @Test
    void statusArgumentsAreChecked() {
        KnowledgeDocument document = curated();
        document.startProcessing(T1);

        assertThatIllegalArgumentException().isThrownBy(() -> document.markReady(-1, T1)).withMessageContaining("chunkCount");
        assertThatIllegalArgumentException().isThrownBy(() -> document.markReady(1, null)).withMessageContaining("now");
        assertThatIllegalArgumentException().isThrownBy(() -> document.markFailed(" ", T1)).withMessageContaining("errorMessage");
        assertThatIllegalArgumentException().isThrownBy(() -> document.markFailed("e".repeat(501), T1));
        assertThatIllegalArgumentException().isThrownBy(() -> document.markFailed("e", null)).withMessageContaining("now");
        assertThatIllegalArgumentException().isThrownBy(() -> document.startProcessing(null)).withMessageContaining("now");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);

        document.markReady(0, T1);
        assertThat(document.getChunkCount()).as("a document may have no chunks").isZero();
    }
}
