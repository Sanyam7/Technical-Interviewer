package com.techinterviewer.interview.port;

import com.techinterviewer.common.validation.Require;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A knowledge-base section a report's study plan points to, as the narrator cited it.
 *
 * @param chunkId the cited chunk
 * @param title   its document's title
 * @param section where in the document (the heading path)
 * @param source  the document's path in the knowledge base
 */
public record StudyReference(UUID chunkId, String title, @Nullable String section, @Nullable String source) {

    public StudyReference {
        Require.nonNull(chunkId, "chunkId");
        Require.notBlank(title, "title", 200);
        section = Require.nullOrNotBlank(section, "section", 500);
        source = Require.nullOrNotBlank(source, "source", 300);
    }
}
