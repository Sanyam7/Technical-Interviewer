package com.techinterviewer.rag.chunking;

import com.techinterviewer.common.validation.Require;

/**
 * A passage cut from a document.
 *
 * @param index       position in the document, from 0
 * @param headingPath where it sits, for example "Java concurrency › The Java memory model and volatile"
 * @param content     the passage text, without the heading path
 * @param tokenCount  estimated tokens of the content
 */
public record Chunk(int index, String headingPath, String content, int tokenCount) {

    public Chunk {
        Require.atLeast(index, 0, "index");
        Require.notBlank(headingPath, "headingPath", 500);
        Require.notBlank(content, "content");
    }

    /**
     * The text that is embedded: the heading path gives a short passage the context it lacks ("it is not atomic" means
     * little without "Java › Concurrency").
     */
    public String embeddingText() {
        return headingPath + "\n\n" + content;
    }
}
