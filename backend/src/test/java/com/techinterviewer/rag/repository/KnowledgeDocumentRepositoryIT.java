package com.techinterviewer.rag.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import com.techinterviewer.rag.DocumentSourceType;
import com.techinterviewer.rag.DocumentStatus;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Visibility;
import com.techinterviewer.rag.domain.KnowledgeDocument;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class KnowledgeDocumentRepositoryIT extends PersistenceTestBase {

    private static final Instant T0 = Fixtures.NOW;

    private KnowledgeDocument save(KnowledgeDocument document) {
        if (document.getVisibility() == Visibility.PUBLIC) {
            deleteAfterTest(document);
        }
        inTransaction(() -> documents.saveAndFlush(document));
        return document;
    }

    private KnowledgeDocument curated(char sha) {
        return save(KnowledgeDocument.curated(
                "corpus/" + sha + ".md", DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha(sha)), Map.of(), T0));
    }

    private KnowledgeDocument resume(UUID owner, char sha) {
        return save(KnowledgeDocument.resume(owner, Fixtures.documentContent(Fixtures.sha(sha)), T0));
    }

    private KnowledgeDocument load(KnowledgeDocument document) {
        return inTransaction(() -> documents.findById(document.getId()).orElseThrow());
    }

    @Test
    void aCuratedDocumentKeepsEveryFieldAndIsPublicWithoutOwner() {
        long skill = skillIds(1).getFirst();
        KnowledgeDocument original = save(KnowledgeDocument.curated(
                "corpus/java/concurrency.md", DocumentType.QUESTION_EXEMPLAR, skill, Fixtures.documentContent(Fixtures.sha('a')),
                Map.of("language", "en", "license", "CC0"), T0));

        KnowledgeDocument loaded = load(original);

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getTitle()).isEqualTo("Java concurrency notes");
        assertThat(loaded.getSourceType()).isEqualTo(DocumentSourceType.CURATED);
        assertThat(loaded.getDocType()).isEqualTo(DocumentType.QUESTION_EXEMPLAR);
        assertThat(loaded.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(loaded.getOwnerUserId()).isNull();
        assertThat(loaded.getSkillId()).isEqualTo(skill);
        assertThat(loaded.getSourcePath()).isEqualTo("corpus/java/concurrency.md");
        assertThat(loaded.getOriginalFilename()).isEqualTo("notes.md");
        assertThat(loaded.getMimeType()).isEqualTo("text/markdown");
        assertThat(loaded.getByteSize()).isEqualTo(1234);
        assertThat(loaded.getContentSha256()).isEqualTo(Fixtures.sha('a')).hasSize(64);
        assertThat(loaded.getExtractedText()).isEqualTo("# Concurrency\nvolatile ...");
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(loaded.getErrorMessage()).isNull();
        assertThat(loaded.getChunkCount()).isZero();
        assertThat(loaded.getMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of("language", "en", "license", "CC0"));
        assertThat(loaded.getCreatedBy()).isNull();
        assertThat(loaded.getCreatedAt()).isEqualTo(T0);
        assertThat(loaded.getUpdatedAt()).isEqualTo(T0);
        assertThat(jdbc.queryForObject("select content_sha256 from knowledge_documents where id = ?", String.class, original.getId()))
                .isEqualTo(Fixtures.sha('a'));
        assertThat(jdbc.queryForObject("select metadata ->> 'license' from knowledge_documents where id = ?", String.class, original.getId()))
                .isEqualTo("CC0");
        assertThat(jdbc.queryForObject("select visibility from knowledge_documents where id = ?", String.class, original.getId())).isEqualTo("PUBLIC");
    }

    @Test
    void anAdminUploadRemembersWhoUploadedItAndIsPublic() {
        UUID admin = newUser();
        KnowledgeDocument original = save(KnowledgeDocument.adminUpload(
                admin, DocumentType.DESIGN_REFERENCE, null, Fixtures.documentContent(Fixtures.sha('b')), Map.of(), T0));

        KnowledgeDocument loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getSourceType()).isEqualTo(DocumentSourceType.ADMIN_UPLOAD);
        assertThat(loaded.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(loaded.getOwnerUserId()).isNull();
        assertThat(loaded.getCreatedBy()).isEqualTo(admin);
        assertThat(loaded.getSourcePath()).isNull();
    }

    @Test
    void aResumeIsPrivateToItsOwnerWhoIsAlsoItsCreator() {
        UUID owner = newUser();
        KnowledgeDocument original = resume(owner, 'c');

        KnowledgeDocument loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getSourceType()).isEqualTo(DocumentSourceType.RESUME);
        assertThat(loaded.getDocType()).isEqualTo(DocumentType.RESUME);
        assertThat(loaded.getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(loaded.getOwnerUserId()).isEqualTo(owner);
        assertThat(loaded.getCreatedBy()).isEqualTo(owner);
        assertThat(loaded.getMetadata()).isEmpty();
    }

    @Test
    void theIngestionLifecycleIsPersisted() {
        KnowledgeDocument document = curated('d');

        inTransaction(() -> documents.findById(document.getId()).orElseThrow().startProcessing(T0.plusSeconds(1)));
        assertThat(load(document).getStatus()).isEqualTo(DocumentStatus.PROCESSING);

        inTransaction(() -> documents.findById(document.getId()).orElseThrow().markReady(7, T0.plusSeconds(2)));
        KnowledgeDocument ready = load(document);
        assertThat(ready.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.getChunkCount()).isEqualTo(7);
        assertThat(ready.getUpdatedAt()).isEqualTo(T0.plusSeconds(2));

        inTransaction(() -> {
            KnowledgeDocument managed = documents.findById(document.getId()).orElseThrow();
            managed.startProcessing(T0.plusSeconds(3));
            managed.markFailed("embedding provider unavailable", T0.plusSeconds(4));
        });
        KnowledgeDocument failed = load(document);
        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("embedding provider unavailable");
        assertThat(jdbc.queryForObject("select status from knowledge_documents where id = ?", String.class, document.getId())).isEqualTo("FAILED");
    }

    @Test
    void theIdentityAndOwnershipColumnsAreNeverUpdated() {
        UUID owner = newUser();
        UUID intruder = newUser();
        KnowledgeDocument document = resume(owner, 'e');

        inTransaction(() -> {
            KnowledgeDocument managed = documents.findById(document.getId()).orElseThrow();
            ReflectionTestUtils.setField(managed, "ownerUserId", intruder);
            ReflectionTestUtils.setField(managed, "visibility", Visibility.PUBLIC);
            managed.startProcessing(T0.plusSeconds(1));
        });

        KnowledgeDocument loaded = load(document);
        assertThat(loaded.getStatus()).as("the update did happen").isEqualTo(DocumentStatus.PROCESSING);
        assertThat(loaded.getOwnerUserId()).isEqualTo(owner);
        assertThat(loaded.getVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    // ---- visibility rules -----------------------------------------------------------------------------------------

    @Test
    void aPublicDocumentIsVisibleToEveryoneAndAPrivateOneOnlyToItsOwner() {
        UUID owner = newUser();
        UUID stranger = newUser();
        KnowledgeDocument publicDocument = curated('a');
        KnowledgeDocument privateDocument = resume(owner, 'b');

        assertThat(documents.findVisibleById(publicDocument.getId(), owner)).isPresent();
        assertThat(documents.findVisibleById(publicDocument.getId(), stranger)).isPresent();
        assertThat(documents.findVisibleById(privateDocument.getId(), owner)).isPresent();
        assertThat(documents.findVisibleById(privateDocument.getId(), stranger)).isEmpty();
        assertThat(documents.findVisibleById(UuidV7.generate(), owner)).isEmpty();
    }

    @Test
    void theVisibleListHoldsPublicAndOwnDocumentsNewestFirstButNeverAnotherUsersPrivateOnes() {
        UUID owner = newUser();
        UUID stranger = newUser();
        KnowledgeDocument oldPublic = save(KnowledgeDocument.curated(
                "corpus/old.md", DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha('1')), Map.of(), T0));
        KnowledgeDocument ownResume = save(KnowledgeDocument.resume(owner, Fixtures.documentContent(Fixtures.sha('2')), T0.plusSeconds(10)));
        KnowledgeDocument newPublic = save(KnowledgeDocument.curated(
                "corpus/new.md", DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha('3')), Map.of(), T0.plusSeconds(20)));
        KnowledgeDocument strangersResume = save(KnowledgeDocument.resume(stranger, Fixtures.documentContent(Fixtures.sha('4')), T0.plusSeconds(30)));

        List<UUID> ids = documents.findPageVisibleTo(owner, PageRequest.of(0, 50)).getContent().stream().map(KnowledgeDocument::getId).toList();

        assertThat(ids).containsSubsequence(newPublic.getId(), ownResume.getId(), oldPublic.getId());
        assertThat(ids).doesNotContain(strangersResume.getId());
        assertThat(documents.findPageVisibleTo(owner, PageRequest.of(0, 1)).getContent()).hasSize(1);
        assertThat(documents.findPageVisibleTo(owner, PageRequest.of(0, 50)).getTotalElements())
                .isEqualTo(documents.findPageVisibleTo(stranger, PageRequest.of(0, 50)).getTotalElements());
    }

    @Test
    void anOwnerLookupNeverReturnsAPublicDocumentOrAnotherUsersDocument() {
        UUID owner = newUser();
        UUID stranger = newUser();
        KnowledgeDocument publicDocument = curated('a');
        KnowledgeDocument privateDocument = resume(owner, 'b');

        assertThat(documents.findByIdAndOwnerUserId(privateDocument.getId(), owner)).isPresent();
        assertThat(documents.findByIdAndOwnerUserId(privateDocument.getId(), stranger)).isEmpty();
        assertThat(documents.findByIdAndOwnerUserId(publicDocument.getId(), owner)).isEmpty();
    }

    @Test
    void duplicateDetectionLooksOnlyAtTheDocumentsOfTheSameOwner() {
        UUID owner = newUser();
        UUID stranger = newUser();
        KnowledgeDocument ownResume = resume(owner, 'a');
        KnowledgeDocument curated = curated('b');

        assertThat(documents.findByOwnerUserIdAndContentSha256(owner, Fixtures.sha('a'))).get().extracting(KnowledgeDocument::getId)
                .isEqualTo(ownResume.getId());
        assertThat(documents.findByOwnerUserIdAndContentSha256(stranger, Fixtures.sha('a'))).isEmpty();
        assertThat(documents.findByOwnerUserIdAndContentSha256(owner, Fixtures.sha('b'))).isEmpty();
        assertThat(documents.findByOwnerUserIdIsNullAndContentSha256(Fixtures.sha('b'))).get().extracting(KnowledgeDocument::getId)
                .isEqualTo(curated.getId());
        assertThat(documents.findByOwnerUserIdIsNullAndContentSha256(Fixtures.sha('a'))).isEmpty();
    }

    @Test
    void aCuratedDocumentIsFoundBySourceTypeAndPath() {
        KnowledgeDocument curated = curated('a');

        assertThat(documents.findBySourceTypeAndSourcePath(DocumentSourceType.CURATED, "corpus/a.md"))
                .get().extracting(KnowledgeDocument::getId).isEqualTo(curated.getId());
        assertThat(documents.findBySourceTypeAndSourcePath(DocumentSourceType.ADMIN_UPLOAD, "corpus/a.md")).isEmpty();
        assertThat(documents.findBySourceTypeAndSourcePath(DocumentSourceType.CURATED, "corpus/other.md")).isEmpty();
    }

    // ---- constraints ----------------------------------------------------------------------------------------------

    @Test
    void oneOwnerCannotStoreTheSameContentTwiceButTwoOwnersCan() {
        UUID first = newUser();
        UUID second = newUser();
        resume(first, 'a');

        assertThatThrownBy(() -> resume(first, 'a')).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(resume(second, 'a').getOwnerUserId()).isEqualTo(second);
    }

    @Test
    void twoPublicDocumentsCannotShareContentBecauseAMissingOwnerCountsAsOneOwner() {
        curated('a');
        KnowledgeDocument sameContent = KnowledgeDocument.curated(
                "corpus/other.md", DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha('a')), Map.of(), T0);

        assertThatThrownBy(() -> inTransaction(() -> documents.saveAndFlush(sameContent))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("knowledge_documents", "content_sha256", Fixtures.sha('a'))).isOne();
    }

    @Test
    void theDatabaseRejectsTheCombinationsTheFactoriesMakeUnrepresentable() {
        UUID owner = newUser();
        String insert = "insert into knowledge_documents (id, title, source_type, doc_type, visibility, owner_user_id, mime_type, byte_size, content_sha256, extracted_text, status)"
                + " values (?, 't', ?, ?, ?, ?, 'text/plain', ?, ?, '', 'PENDING')";
        String sha = Fixtures.sha('f');

        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), "CURATED", "REFERENCE", "PRIVATE", null, 1, sha))
                .as("private without owner").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), "RESUME", "RESUME", "PUBLIC", null, 1, sha))
                .as("public resume").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), "CURATED", "RESUME", "PUBLIC", null, 1, sha))
                .as("public resume document type").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), "CURATED", "REFERENCE", "PUBLIC", null, -1, sha))
                .as("negative size").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), "CURATED", "REFERENCE", "PUBLIC", null, 1, "F".repeat(64)))
                .as("upper-case hash").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), "CURATED", "REFERENCE", "PUBLIC", null, 1, "f".repeat(63)))
                .as("short hash").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), "CURATED", "REFERENCE", "SECRET", null, 1, sha))
                .as("unknown visibility").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, UuidV7.generate(), "RESUME", "RESUME", "PRIVATE", owner, 1, sha)).as("the legitimate combination").isOne();
    }

    @Test
    void aDocumentCannotReferToAnUnknownSkillOrCreator() {
        KnowledgeDocument unknownSkill = KnowledgeDocument.curated(
                "corpus/x.md", DocumentType.REFERENCE, Long.MAX_VALUE, Fixtures.documentContent(Fixtures.sha('a')), Map.of(), T0);
        KnowledgeDocument unknownCreator = KnowledgeDocument.adminUpload(
                UuidV7.generate(), DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha('b')), Map.of(), T0);

        assertThatThrownBy(() -> inTransaction(() -> documents.saveAndFlush(unknownSkill))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> documents.saveAndFlush(unknownCreator))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDocumentsOfAUserAreDeletedWithTheUserWhilePublicUploadsOnlyLoseTheirCreator() {
        UUID owner = newUser();
        UUID admin = newUser();
        KnowledgeDocument resume = resume(owner, 'a');
        KnowledgeDocument upload = save(KnowledgeDocument.adminUpload(
                admin, DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha('b')), Map.of(), T0));

        jdbc.update("delete from users where id = ?", owner);
        jdbc.update("delete from users where id = ?", admin);

        assertThat(count("knowledge_documents", "id", resume.getId())).isZero();
        assertThat(count("knowledge_documents", "id", upload.getId())).as("a public upload outlives its uploader").isOne();
        assertThat(load(upload).getCreatedBy()).isNull();
    }
}
