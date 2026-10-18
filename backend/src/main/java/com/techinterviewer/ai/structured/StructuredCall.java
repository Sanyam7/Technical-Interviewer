package com.techinterviewer.ai.structured;

import com.techinterviewer.ai.prompt.PromptTemplate;
import com.techinterviewer.ai.prompt.PromptValue;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One structured AI operation, as an agent describes it.
 *
 * @param task        routing and recording
 * @param prompt      the template
 * @param values      its placeholder values (untrusted text marked as such)
 * @param schema      the JSON Schema the provider constrains the answer to, possibly narrowed for this request
 * @param type        the record the JSON is bound to
 * @param check       semantic checks that a schema cannot express (topic equals the requested one, no answer leakage,
 *                    every key point assessed, ...). Returns the problems; empty means accepted
 * @param interviewId recorded with every call
 * @param contextChunkIds the knowledge chunks the prompt contains, recorded with every call; null when the operation
 *                    used no retrieval
 * @param <T>         the output type
 */
public record StructuredCall<T>(
        AiTask task,
        PromptTemplate prompt,
        Map<String, PromptValue> values,
        JsonNode schema,
        Class<T> type,
        Function<T, List<String>> check,
        @Nullable UUID interviewId,
        @Nullable List<UUID> contextChunkIds) {

    public StructuredCall {
        Require.nonNull(task, "task");
        Require.nonNull(prompt, "prompt");
        values = Map.copyOf(values);
        Require.nonNull(schema, "schema");
        Require.nonNull(type, "type");
        Require.nonNull(check, "check");
        contextChunkIds = contextChunkIds == null ? null : List.copyOf(contextChunkIds);
    }

    /** An operation that uses no retrieval. */
    public StructuredCall(
            AiTask task,
            PromptTemplate prompt,
            Map<String, PromptValue> values,
            JsonNode schema,
            Class<T> type,
            Function<T, List<String>> check,
            @Nullable UUID interviewId) {
        this(task, prompt, values, schema, type, check, interviewId, null);
    }
}
