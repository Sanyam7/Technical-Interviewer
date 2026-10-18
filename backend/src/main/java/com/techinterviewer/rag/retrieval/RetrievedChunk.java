package com.techinterviewer.rag.retrieval;

import com.techinterviewer.rag.DocumentType;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A chunk found by a search, with where it came from and why it ranked where it did.
 *
 * @param chunkId      the chunk
 * @param title        title of its document
 * @param sourcePath   its document's path in the knowledge base
 * @param technology   its document's technology slug
 * @param docType      its document's type
 * @param headingPath  where in the document it sits
 * @param content      the passage
 * @param tokenCount   estimated tokens of the passage
 * @param topicKey     {@code skill/topic} when the document has a catalog topic
 * @param vectorRank   1-based rank in the vector half, null when the vector half did not find it
 * @param keywordRank  1-based rank in the keyword half, null when the keyword half did not find it
 * @param similarity   cosine similarity to the query, when the vector half found it
 * @param score        fused score; higher is better
 */
public record RetrievedChunk(
        UUID chunkId,
        String title,
        @Nullable String sourcePath,
        @Nullable String technology,
        DocumentType docType,
        @Nullable String headingPath,
        String content,
        int tokenCount,
        @Nullable String topicKey,
        @Nullable Integer vectorRank,
        @Nullable Integer keywordRank,
        @Nullable Double similarity,
        double score) {
}
