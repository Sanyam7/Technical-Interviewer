package com.techinterviewer.rag.corpus;

import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Technology;
import org.jspecify.annotations.Nullable;

/**
 * A parsed knowledge document: its front matter and its Markdown body.
 *
 * @param sourcePath    path inside the knowledge base, for example {@code java/concurrency.md}; identifies the document
 * @param title         the document title, the root of every heading path
 * @param technology    the one technology it is about
 * @param skillSlug     the catalog skill it belongs to, if any
 * @param topicSlug     the catalog topic of that skill, if any (requires a skill)
 * @param docType       REFERENCE, DESIGN_REFERENCE or QUESTION_EXEMPLAR
 * @param difficulty    1 to 5 when the whole document is at one level; null for mixed material
 * @param body          the Markdown after the front matter
 * @param contentSha256 hex SHA-256 of the whole file, the change detector
 * @param byteSize      size of the file
 */
public record CorpusDocument(
        String sourcePath,
        String title,
        Technology technology,
        @Nullable String skillSlug,
        @Nullable String topicSlug,
        DocumentType docType,
        @Nullable Integer difficulty,
        String body,
        String contentSha256,
        int byteSize) {

    /** {@code skill/topic}, the key questions and chunks share; null without a topic. */
    public @Nullable String topicKey() {
        return topicSlug == null ? null : skillSlug + "/" + topicSlug;
    }
}
