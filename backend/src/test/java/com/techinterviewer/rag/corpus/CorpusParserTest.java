package com.techinterviewer.rag.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Technology;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Reading knowledge documents, and rejecting malformed ones with every problem named. */
class CorpusParserTest {

    private static final String VALID = """
            ---
            title: Java concurrency
            technology: java
            skill: java
            topic: concurrency
            doc_type: REFERENCE
            difficulty: 3
            ---

            ## Race conditions

            count++ is not atomic.
            """;

    private static CorpusDocument parse(String text) {
        return CorpusParser.parse("java/concurrency.md", text.getBytes(StandardCharsets.UTF_8));
    }

    private static MalformedDocumentException rejected(String text) {
        try {
            parse(text);
        } catch (MalformedDocumentException expected) {
            return expected;
        }
        throw new AssertionError("the document was accepted");
    }

    @Test
    void aValidDocumentGivesItsFrontMatterBodyAndHash() {
        CorpusDocument document = parse(VALID);

        assertThat(document.sourcePath()).isEqualTo("java/concurrency.md");
        assertThat(document.title()).isEqualTo("Java concurrency");
        assertThat(document.technology()).isEqualTo(Technology.JAVA);
        assertThat(document.skillSlug()).isEqualTo("java");
        assertThat(document.topicKey()).isEqualTo("java/concurrency");
        assertThat(document.docType()).isEqualTo(DocumentType.REFERENCE);
        assertThat(document.difficulty()).isEqualTo(3);
        assertThat(document.body()).startsWith("## Race conditions").endsWith("count++ is not atomic.");
        assertThat(document.contentSha256()).hasSize(64).isEqualTo(CorpusParser.sha256(VALID.getBytes(StandardCharsets.UTF_8)));
        assertThat(document.byteSize()).isEqualTo(VALID.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void windowsLineEndingsAndAByteOrderMarkAreAccepted() {
        assertThat(parse("﻿" + VALID.replace("\n", "\r\n")).title()).isEqualTo("Java concurrency");
    }

    @Test
    void optionalFieldsMayBeLeftOut() {
        CorpusDocument document = parse("---\ntitle: Git\ntechnology: git\ndoc_type: REFERENCE\n---\nCommits are snapshots.\n");

        assertThat(document.skillSlug()).isNull();
        assertThat(document.topicKey()).isNull();
        assertThat(document.difficulty()).isNull();
    }

    @Test
    void aDocumentWithoutFrontMatterOrWithAnUnclosedOneIsRejected() {
        assertThat(rejected("## Just markdown\ntext").problems()).containsExactly("no front matter: the file must start with a --- line");
        assertThat(rejected("---\ntitle: x\n## no end").problems()).containsExactly("the front matter is not closed by a --- line");
    }

    @Test
    void everyProblemOfTheFrontMatterIsReportedAtOnce() {
        MalformedDocumentException failure = rejected("""
                ---
                technology: kotlin
                topic: coroutines
                doc_type: RESUME
                difficulty: 9
                author: me
                technology: java
                not a key value line
                ---
                ## Heading only
                """);

        assertThat(failure.problems()).containsExactlyInAnyOrder(
                "unknown front matter key \"author\"",
                "front matter key \"technology\" is repeated",
                "front matter line \"not a key value line\" is not key: value",
                "title is required",
                "technology \"kotlin\" is not one of [java, spring-boot, react, javascript, typescript, nodejs, sql, rest, "
                        + "microservices, databases, system-design, data-structures, algorithms, docker, cloud, git]",
                "doc_type RESUME is private to a candidate and never part of the knowledge base",
                "topic requires skill",
                "difficulty must be 1 to 5 but was 9",
                "the document has no text after its front matter");
        assertThat(failure.getMessage()).startsWith("java/concurrency.md: ");
    }

    @Test
    void anUnknownDocTypeAWrongSlugAndANonNumericDifficultyAreRejected() {
        assertThat(rejected("---\ntitle: T\ntechnology: java\ndoc_type: NOTES\nskill: Java Core\ndifficulty: hard\n---\ntext\n").problems())
                .containsExactlyInAnyOrder(
                        "doc_type \"NOTES\" is not REFERENCE, DESIGN_REFERENCE or QUESTION_EXEMPLAR",
                        "skill \"Java Core\" is not a lower-case slug",
                        "difficulty must be a number from 1 to 5");
    }

    @Test
    void anOversizedFileIsRejectedBeforeItIsRead() {
        byte[] huge = new byte[CorpusParser.MAX_BYTES + 1];
        assertThatThrownBy(() -> CorpusParser.parse("big.md", huge))
                .isInstanceOf(MalformedDocumentException.class).hasMessageContaining("larger than");
    }
}
