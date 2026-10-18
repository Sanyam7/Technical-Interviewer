package com.techinterviewer.resume.parse;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.resume.ResumeFileType;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads the text of an uploaded résumé and decides whether it can be used (AI_ARCHITECTURE.md section 2.13). Deterministic and
 * free of any model.
 *
 * <ol>
 *   <li><b>Size:</b> 1 byte to {@link ResumeLimits#MAX_FILE_BYTES}, else 413 (or {@code DOCUMENT_EMPTY} for nothing).
 *   <li><b>Type, from the content only:</b> a PDF starts with {@code %PDF-}; a .docx is a zip archive with a
 *       {@code word/document.xml} part; anything else must be strict UTF-8 text without control characters (an image, an old
 *       binary .doc, an executable or RTF is not). The client's content type and file name are never consulted. Otherwise
 *       415 {@code UNSUPPORTED_FILE_TYPE}.
 *   <li><b>Content:</b> the text is extracted ({@link PdfText}, {@link DocxText}) and cleaned ({@link ResumeText}); a damaged
 *       or protected document is 422 {@code DOCUMENT_UNREADABLE}, fewer than {@link ResumeLimits#MIN_LETTERS} letters (a scan,
 *       a blank page) 422 {@code DOCUMENT_EMPTY}, more than {@link ResumeLimits#MAX_TEXT_CHARS} characters or
 *       {@link ResumeLimits#MAX_PAGES} pages 422 {@code DOCUMENT_TOO_LONG}.
 * </ol>
 *
 * Whether the text is a résumé at all is decided later, by what can be verified of the extracted profile.
 */
public final class ResumeParser {

    static final String SUPPORTED = "Upload a PDF, a Word document (.docx) or a plain-text file.";

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ResumeParser() {
    }

    public static ParsedResume parse(byte[] bytes) {
        if (bytes.length == 0) {
            throw new ApiException(ErrorCode.DOCUMENT_EMPTY, "The file is empty.");
        }
        if (bytes.length > ResumeLimits.MAX_FILE_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "A résumé may be at most " + ResumeLimits.MAX_FILE_BYTES / (1024 * 1024) + " MB.");
        }
        ResumeFileType type = detect(bytes);
        Integer pages = null;
        String raw;
        switch (type) {
            case PDF -> {
                PdfText.Extracted extracted = PdfText.extract(bytes);
                raw = extracted.text();
                pages = extracted.pages();
            }
            case DOCX -> raw = DocxText.extract(bytes);
            case TEXT -> raw = utf8(bytes);
            default -> throw new IllegalStateException("unexpected type " + type);
        }
        String text = ResumeText.clean(raw);
        if (ResumeText.letters(text) < ResumeLimits.MIN_LETTERS) {
            throw new ApiException(ErrorCode.DOCUMENT_EMPTY, type == ResumeFileType.PDF
                    ? "The PDF has no readable text. A scanned résumé cannot be read: export it from your editor instead."
                    : "The document has no readable text.");
        }
        if (text.length() > ResumeLimits.MAX_TEXT_CHARS) {
            throw new ApiException(ErrorCode.DOCUMENT_TOO_LONG,
                    "The document has " + text.length() + " characters of text; a résumé may have at most " + ResumeLimits.MAX_TEXT_CHARS + ".");
        }
        return new ParsedResume(type, pages, text);
    }

    /** The type of a document, from its first bytes and, for text, from whether it is clean UTF-8. */
    static ResumeFileType detect(byte[] bytes) {
        if (startsWith(bytes, PDF_SIGNATURE)) {
            return ResumeFileType.PDF;
        }
        if (startsWith(bytes, ZIP_SIGNATURE)) {
            return ResumeFileType.DOCX;
        }
        String text = decode(bytes);
        if (text == null || !plainText(text)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "The file is not a supported document. " + SUPPORTED);
        }
        return ResumeFileType.TEXT;
    }

    private static String utf8(byte[] bytes) {
        String text = decode(bytes);
        if (text == null) {
            throw new IllegalStateException("detect() accepted text that is not UTF-8");
        }
        return text;
    }

    /** Strict UTF-8 (a malformed or unmappable byte fails), without a leading byte order mark; null when it is not UTF-8. */
    private static String decode(byte[] bytes) {
        int offset = startsWith(bytes, UTF8_BOM) ? UTF8_BOM.length : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return null;
        }
    }

    /** Text has no control characters other than tab, line feed, carriage return and form feed, and is not RTF markup. */
    private static boolean plainText(String text) {
        if (text.startsWith("{\\rtf")) {
            return false;
        }
        return text.chars().noneMatch(c -> (c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f') || c == 0x7F);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
