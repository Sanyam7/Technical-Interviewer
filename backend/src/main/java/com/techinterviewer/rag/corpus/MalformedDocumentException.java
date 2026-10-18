package com.techinterviewer.rag.corpus;

import java.util.List;

/** A knowledge document that cannot be ingested, with every problem found in it. */
public final class MalformedDocumentException extends RuntimeException {

    private final transient List<String> problems;

    public MalformedDocumentException(String sourcePath, List<String> problems) {
        super(sourcePath + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
