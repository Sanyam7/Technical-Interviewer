package com.techinterviewer.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.techinterviewer.persistence.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentContentTest {

    private static final String SHA = Fixtures.sha('a');

    @Test
    void validContentIsKept() {
        DocumentContent content = new DocumentContent("Title", "notes.md", "text/markdown", 10, SHA, "text");

        assertThat(content.title()).isEqualTo("Title");
        assertThat(content.originalFilename()).isEqualTo("notes.md");
        assertThat(content.mimeType()).isEqualTo("text/markdown");
        assertThat(content.byteSize()).isEqualTo(10);
        assertThat(content.contentSha256()).isEqualTo(SHA);
        assertThat(content.extractedText()).isEqualTo("text");
    }

    @Test
    void theFilenameIsOptionalAndTheTextMayBeEmpty() {
        DocumentContent content = new DocumentContent("Title", null, "text/plain", 0, SHA, "");

        assertThat(content.originalFilename()).isNull();
        assertThat(content.extractedText()).isEmpty();
        assertThat(content.byteSize()).isZero();
    }

    @ParameterizedTest(name = "[{0}] is not a lower-case SHA-256")
    @ValueSource(strings = {"", "abc", "A", "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
        "g123456789012345678901234567890123456789012345678901234567890123",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
        " 123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})
    void theHashMustBeSixtyFourLowerCaseHexCharacters(String sha) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("T", null, "text/plain", 1, sha, "t")).withMessageContaining("contentSha256");
    }

    @Test
    void aMissingHashIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("T", null, "text/plain", 1, null, "t")).withMessageContaining("contentSha256");
    }

    @Test
    void textsAreBoundedLikeTheirColumns() {
        assertThat(new DocumentContent("t".repeat(200), "f".repeat(255), "m".repeat(60), 1, SHA, "x").title()).hasSize(200);
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("t".repeat(201), null, "m", 1, SHA, "x")).withMessageContaining("title");
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("t", "f".repeat(256), "m", 1, SHA, "x")).withMessageContaining("originalFilename");
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("t", null, "m".repeat(61), 1, SHA, "x")).withMessageContaining("mimeType");
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent(" ", null, "m", 1, SHA, "x")).withMessageContaining("title");
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("t", null, "", 1, SHA, "x")).withMessageContaining("mimeType");
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("t", " ", "m", 1, SHA, "x")).withMessageContaining("originalFilename");
    }

    @Test
    void theSizeCannotBeNegativeAndTheTextIsRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("t", null, "m", -1, SHA, "x")).withMessageContaining("byteSize");
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentContent("t", null, "m", 1, SHA, null)).withMessageContaining("extractedText");
    }
}
