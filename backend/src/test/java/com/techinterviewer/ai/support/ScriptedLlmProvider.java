package com.techinterviewer.ai.support;

import com.techinterviewer.ai.provider.FinishReason;
import com.techinterviewer.ai.provider.LlmException;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.LlmRequest;
import com.techinterviewer.ai.provider.LlmResponse;
import com.techinterviewer.ai.provider.TokenUsage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Test double of a provider (AI_ARCHITECTURE.md 9.1): answers from a script instead of a model, and records every
 * request so tests can inspect the rendered prompt, the schema and the time limit. Steps are used in order; when the
 * script is used up, the fallback answers (by default it fails the test).
 */
public final class ScriptedLlmProvider implements LlmProvider {

    private final String id;
    private final Deque<Function<LlmRequest, LlmResponse>> steps = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();
    private Function<LlmRequest, LlmResponse> fallback = request -> {
        throw new AssertionError("unexpected provider call #" + requests.size());
    };

    public ScriptedLlmProvider() {
        this("gemini");
    }

    public ScriptedLlmProvider(String id) {
        this.id = id;
    }

    /** The next call answers this text, finished normally. */
    public ScriptedLlmProvider thenAnswer(String text) {
        return thenAnswer(FinishReason.STOP, text);
    }

    public ScriptedLlmProvider thenAnswer(FinishReason finish, String text) {
        steps.add(request -> response(finish, text));
        return this;
    }

    /** The next call computes its answer from the request. */
    public ScriptedLlmProvider thenAnswer(Function<LlmRequest, String> answer) {
        steps.add(request -> response(FinishReason.STOP, answer.apply(request)));
        return this;
    }

    /** The next call fails. */
    public ScriptedLlmProvider thenFail(LlmException failure) {
        steps.add(request -> {
            throw failure;
        });
        return this;
    }

    /** Every call after the script answers this way. */
    public ScriptedLlmProvider always(Function<LlmRequest, String> answer) {
        fallback = request -> response(FinishReason.STOP, answer.apply(request));
        return this;
    }

    public ScriptedLlmProvider alwaysFail(LlmException failure) {
        fallback = request -> {
            throw failure;
        };
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        requests.add(request);
        Function<LlmRequest, LlmResponse> step = steps.isEmpty() ? fallback : steps.poll();
        return step.apply(request);
    }

    public synchronized List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized LlmRequest lastRequest() {
        return requests.getLast();
    }

    public synchronized int calls() {
        return requests.size();
    }

    private static LlmResponse response(FinishReason finish, String text) {
        return new LlmResponse(text, finish, new TokenUsage(120, 80, 10), "scripted-001", Duration.ofMillis(5));
    }
}
