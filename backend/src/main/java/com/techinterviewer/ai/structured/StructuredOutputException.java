package com.techinterviewer.ai.structured;

import com.techinterviewer.ai.routing.AiTask;
import java.util.List;

/**
 * The model's answers stayed unusable after the repair attempt. Every attempt is in {@code llm_calls} with status
 * {@code INVALID_OUTPUT}. Agents translate it into the failure their port defines.
 */
public final class StructuredOutputException extends RuntimeException {

    private final AiTask task;
    private final List<String> problems;

    public StructuredOutputException(AiTask task, int attempts, List<String> problems) {
        super(task + ": the model's output was still invalid after " + attempts + " attempt(s): " + String.join("; ", problems));
        this.task = task;
        this.problems = List.copyOf(problems);
    }

    public AiTask task() {
        return task;
    }

    public List<String> problems() {
        return problems;
    }
}
