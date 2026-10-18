package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.validation.Require;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A knowledge-base section the study plan points to, as stored in {@code reports.narrative}. The chunk id is kept for
 * traceability; the title, section and source are copied so the report reads the same after the knowledge base changes.
 *
 * @param chunkId the cited chunk (no foreign key: the report outlives a re-indexed knowledge base)
 * @param title   its document's title
 * @param section the heading path
 * @param source  the document's path in the knowledge base
 */
public record ReadingEntry(UUID chunkId, String title, @Nullable String section, @Nullable String source) {

    public ReadingEntry {
        Require.nonNull(chunkId, "chunkId");
        Require.notBlank(title, "title", 200);
    }
}
