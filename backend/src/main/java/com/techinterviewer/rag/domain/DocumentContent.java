package com.techinterviewer.rag.domain;

import com.techinterviewer.common.validation.Require;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The extracted content of a document and what identifies it, apart from where it came from and who may see it.
 *
 * @param title            at most 200 characters
 * @param originalFilename the uploaded file name, absent for curated material
 * @param mimeType         at most 60 characters
 * @param byteSize         size of the source file, not negative
 * @param contentSha256    lower-case hex SHA-256 of the source (64 characters), the change and duplicate detector
 * @param extractedText    the text; kept so the document can be re-chunked without a re-upload (may be empty when
 *                         extraction found nothing)
 */
public record DocumentContent(
        String title,
        @Nullable String originalFilename,
        String mimeType,
        int byteSize,
        String contentSha256,
        String extractedText) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_FILENAME_LENGTH = 255;
    public static final int MAX_MIME_TYPE_LENGTH = 60;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public DocumentContent {
        Require.notBlank(title, "title", MAX_TITLE_LENGTH);
        originalFilename = Require.nullOrNotBlank(originalFilename, "originalFilename", MAX_FILENAME_LENGTH);
        Require.notBlank(mimeType, "mimeType", MAX_MIME_TYPE_LENGTH);
        Require.atLeast(byteSize, 0, "byteSize");
        Require.nonNull(contentSha256, "contentSha256");
        if (!SHA256_HEX.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("contentSha256 must be 64 lower-case hex characters");
        }
        Require.nonNull(extractedText, "extractedText");
    }
}
