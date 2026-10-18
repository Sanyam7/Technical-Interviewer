package com.techinterviewer.ai.structured;

import java.util.List;

/** One model answer could not be accepted; the problems are shown to the model in the repair attempt. */
public final class OutputRejectedException extends RuntimeException {

    private final List<String> problems;

    public OutputRejectedException(List<String> problems) {
        super(String.join("; ", problems));
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("a rejection needs at least one problem");
        }
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
