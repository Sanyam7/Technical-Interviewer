package com.techinterviewer.resume.parse;

import static com.techinterviewer.resume.ResumeFiles.RESUME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.resume.ResumeFileType;
import com.techinterviewer.resume.ResumeFiles;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The deterministic half of the upload (AI_ARCHITECTURE.md section 2.13): what kind of file it is (from its content only),
 * whether its text can be read, and the limits. The cases the feature must handle: a valid résumé, an empty one, a malformed
 * document, an unsupported file (prompt injection is the extractor's and the verifier's concern).
 */
class ResumeParserTest {

    private static void rejected(ThrowingCallable parse, ErrorCode code) {
        assertThatThrownBy(parse).isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }

    @Nested
    class ValidResumes {

        @Test
        void aPdfIsReadPageByPageWithItsPageCount() {
            ParsedResume parsed = ResumeParser.parse(ResumeFiles.pdf(RESUME, "References available on request from previous employers and clients."));

            assertThat(parsed.type()).isEqualTo(ResumeFileType.PDF);
            assertThat(parsed.pageCount()).isEqualTo(2);
            assertThat(parsed.text()).contains("Built a Spring Boot microservice using Kafka to process card payment events.")
                    .contains("References available on request");
        }

        @Test
        void aWordDocumentIsReadParagraphByParagraph() {
            ParsedResume parsed = ResumeParser.parse(ResumeFiles.docx(RESUME + "\nR&D lead for <internal> tools"));

            assertThat(parsed.type()).isEqualTo(ResumeFileType.DOCX);
            assertThat(parsed.pageCount()).isNull();
            assertThat(parsed.text()).contains("Senior Software Engineer, Acme Payments - Mar 2021 to Present\n- Built a Spring Boot")
                    .contains("R&D lead for <internal> tools");
        }

        @Test
        void plainTextAndMarkdownAreRead() {
            ParsedResume text = ResumeParser.parse(ResumeFiles.utf8(RESUME));
            ParsedResume markdown = ResumeParser.parse(ResumeFiles.utf8("# Ada Lovelace\n\n## Experience\n\n" + RESUME));

            assertThat(text.type()).isEqualTo(ResumeFileType.TEXT);
            assertThat(text.text()).startsWith("Ada Lovelace\nada.lovelace@example.com");
            assertThat(markdown.type()).isEqualTo(ResumeFileType.TEXT);
            assertThat(markdown.text()).startsWith("# Ada Lovelace");
        }

        @Test
        void aByteOrderMarkIsNotPartOfTheText() {
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] text = ResumeFiles.utf8(RESUME);
            byte[] both = Arrays.copyOf(bom, bom.length + text.length);
            System.arraycopy(text, 0, both, bom.length, text.length);

            assertThat(ResumeParser.parse(both).text()).startsWith("Ada Lovelace");
        }

        @Test
        void theTypeComesFromTheContentNotFromANameOrDeclaredType() {
            // The parser never sees the file name or the client's content type: the same bytes are the same document.
            assertThat(ResumeParser.detect(ResumeFiles.pdf(RESUME))).isEqualTo(ResumeFileType.PDF);
            assertThat(ResumeParser.detect(ResumeFiles.utf8(RESUME))).isEqualTo(ResumeFileType.TEXT);
            assertThat(ResumeParser.detect(ResumeFiles.docx(RESUME))).isEqualTo(ResumeFileType.DOCX);
        }
    }

    @Nested
    class EmptyResumes {

        @Test
        void anEmptyFileIsEmpty() {
            rejected(() -> ResumeParser.parse(new byte[0]), ErrorCode.DOCUMENT_EMPTY);
        }

        @Test
        void whiteSpaceAndInvisibleCharactersAreNoText() {
            rejected(() -> ResumeParser.parse(ResumeFiles.utf8("   \n\n\t ​​ \n")), ErrorCode.DOCUMENT_EMPTY);
        }

        @Test
        void aPdfWithoutTextIsEmptyAsAScanWouldBe() {
            rejected(() -> ResumeParser.parse(ResumeFiles.pdf("", "")), ErrorCode.DOCUMENT_EMPTY);
        }

        @Test
        void aWordDocumentWithoutParagraphsIsEmpty() {
            rejected(() -> ResumeParser.parse(ResumeFiles.docxWithDocumentXml(ResumeFiles.documentXml(""))), ErrorCode.DOCUMENT_EMPTY);
        }

        @Test
        void aFewWordsAreNotEnoughText() {
            rejected(() -> ResumeParser.parse(ResumeFiles.utf8("Ada Lovelace, engineer.")), ErrorCode.DOCUMENT_EMPTY);
        }
    }

    @Nested
    class MalformedDocuments {

        @Test
        void aPdfWithAHeaderAndNothingElseCannotBeRead() {
            rejected(() -> ResumeParser.parse("%PDF-1.7\nthis is not a PDF body at all, no objects, no trailer".getBytes(StandardCharsets.US_ASCII)),
                    ErrorCode.DOCUMENT_UNREADABLE);
        }

        @Test
        void aTruncatedPdfCannotBeRead() {
            byte[] pdf = ResumeFiles.pdf(RESUME);
            rejected(() -> ResumeParser.parse(Arrays.copyOf(pdf, 120)), ErrorCode.DOCUMENT_UNREADABLE);
        }

        @Test
        void aPasswordProtectedPdfIsRefused() {
            rejected(() -> ResumeParser.parse(ResumeFiles.encryptedPdf(RESUME)), ErrorCode.DOCUMENT_UNREADABLE);
        }

        @Test
        void aWordDocumentWithBrokenXmlCannotBeRead() {
            rejected(() -> ResumeParser.parse(ResumeFiles.docxWithDocumentXml("<w:document><w:body><w:p>unclosed")), ErrorCode.DOCUMENT_UNREADABLE);
        }

        @Test
        void aCorruptZipCannotBeRead() {
            byte[] docx = ResumeFiles.docx(RESUME);
            byte[] corrupt = Arrays.copyOf(docx, docx.length);
            for (int i = 40; i < corrupt.length - 40; i++) {
                corrupt[i] = (byte) (corrupt[i] ^ 0x5A);
            }
            rejected(() -> ResumeParser.parse(corrupt), ErrorCode.DOCUMENT_UNREADABLE);
        }

        @Test
        void anExternalEntityIsNeverResolved() {
            String xxe = "<?xml version=\"1.0\"?><!DOCTYPE w:document [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>&secret;"
                    + "</w:t></w:r></w:p></w:body></w:document>";

            rejected(() -> ResumeParser.parse(ResumeFiles.docxWithDocumentXml(xxe)), ErrorCode.DOCUMENT_UNREADABLE);
        }

        @Test
        void aZipBombStopsAtTheExpansionLimit() {
            byte[] huge = new byte[ResumeLimits.MAX_DOCX_XML_BYTES + 1024];
            Arrays.fill(huge, (byte) ' ');
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("word/document.xml", huge);
            byte[] bomb = ResumeFiles.zip(entries);

            assertThat(bomb.length).as("compressed").isLessThan(ResumeLimits.MAX_FILE_BYTES / 50);
            rejected(() -> ResumeParser.parse(bomb), ErrorCode.DOCUMENT_UNREADABLE);
        }
    }

    @Nested
    class UnsupportedFiles {

        @Test
        void imagesAreNotDocuments() {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
            byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

            rejected(() -> ResumeParser.parse(png), ErrorCode.UNSUPPORTED_FILE_TYPE);
            rejected(() -> ResumeParser.parse(jpeg), ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        @Test
        void theOldBinaryWordFormatIsNotSupported() {
            byte[] doc = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0, 0, 0};
            rejected(() -> ResumeParser.parse(doc), ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        @Test
        void anArchiveThatIsNotAWordDocumentIsNotSupported() {
            Map<String, byte[]> spreadsheet = new LinkedHashMap<>();
            spreadsheet.put("xl/workbook.xml", "<workbook/>".getBytes(StandardCharsets.UTF_8));
            rejected(() -> ResumeParser.parse(ResumeFiles.zip(spreadsheet)), ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        @Test
        void rtfAndBinaryDataAreNotPlainText() {
            rejected(() -> ResumeParser.parse(ResumeFiles.utf8("{\\rtf1\\ansi " + RESUME + "}")), ErrorCode.UNSUPPORTED_FILE_TYPE);
            rejected(() -> ResumeParser.parse(ResumeFiles.utf8(RESUME + "\u0000\u0001\u0002")), ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        @Test
        void textThatIsNotUtf8IsNotSupported() {
            rejected(() -> ResumeParser.parse((RESUME + " Café résumé").getBytes(StandardCharsets.ISO_8859_1)), ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
    }

    @Nested
    class Limits {

        @Test
        void aFileOverTwoMegabytesIsTooLarge() {
            byte[] big = new byte[ResumeLimits.MAX_FILE_BYTES + 1];
            Arrays.fill(big, (byte) 'a');
            rejected(() -> ResumeParser.parse(big), ErrorCode.PAYLOAD_TOO_LARGE);
        }

        @Test
        void aPdfOfMoreThanTenPagesIsTooLong() {
            String[] pages = Collections.nCopies(ResumeLimits.MAX_PAGES + 1, RESUME).toArray(String[]::new);
            rejected(() -> ResumeParser.parse(ResumeFiles.pdf(pages)), ErrorCode.DOCUMENT_TOO_LONG);
        }

        @Test
        void moreTextThanAResumeHoldsIsTooLong() {
            String text = (RESUME + "\n").repeat(ResumeLimits.MAX_TEXT_CHARS / RESUME.length() + 2);
            rejected(() -> ResumeParser.parse(ResumeFiles.utf8(text)), ErrorCode.DOCUMENT_TOO_LONG);
        }
    }
}
