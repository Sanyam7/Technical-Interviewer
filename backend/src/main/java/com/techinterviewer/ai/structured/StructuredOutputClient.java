package com.techinterviewer.ai.structured;

import com.techinterviewer.ai.observability.LlmCallRecord;
import com.techinterviewer.ai.observability.LlmCallRecorder;
import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.ai.prompt.PromptRenderer;
import com.techinterviewer.ai.provider.ContentBlockedException;
import com.techinterviewer.ai.provider.LlmException;
import com.techinterviewer.ai.provider.LlmRequest;
import com.techinterviewer.ai.provider.LlmResponse;
import com.techinterviewer.ai.provider.LlmTimeoutException;
import com.techinterviewer.ai.provider.ProviderUnavailableException;
import com.techinterviewer.ai.provider.RateLimitedException;
import com.techinterviewer.ai.routing.ModelRoute;
import com.techinterviewer.ai.routing.ModelRouter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * The single way an agent talks to a model (AI_ARCHITECTURE.md section 5). For one {@link StructuredCall} it:
 * <ol>
 *   <li>routes the task to its provider and model and renders the prompt;
 *   <li>sends it with the JSON Schema, which the provider enforces while generating;
 *   <li>retries a rate limit, an outage or a timeout with exponential backoff and jitter ({@code Retry-After}
 *       honoured), at most the provider's {@code max-retries} times; a rejected request or blocked content is never
 *       retried;
 *   <li>parses and validates the answer ({@link OutputParser}), rejects one that recites the prompt ({@link PromptEcho}) and
 *       runs the agent's semantic checks;
 *   <li>if the answer is rejected, asks once more with the list of problems (the <i>repair</i> attempt);
 *   <li>records every call in {@code llm_calls} with its status ({@code OK}, {@code REPAIRED},
 *       {@code INVALID_OUTPUT}, {@code RATE_LIMITED}, {@code TIMEOUT}, {@code BLOCKED}, {@code ERROR}).
 * </ol>
 * The whole operation runs within the route's <b>call budget</b>: every call's time limit is cut to what is left of
 * it, no retry or repair starts after it, and so the operation ends within the budget whatever the provider does.
 *
 * <p>Never call it inside a database transaction: it blocks for as long as the model takes.
 */
public final class StructuredOutputClient {

    /** The first answer plus one repair. */
    public static final int MAX_STRUCTURED_ATTEMPTS = 2;

    static final Duration BACKOFF_INITIAL = Duration.ofMillis(500);
    static final Duration BACKOFF_MAX = Duration.ofSeconds(8);

    private final ModelRouter router;
    private final PromptRenderer renderer;
    private final OutputParser parser;
    private final LlmCallRecorder recorder;
    private final Clock clock;
    private final Sleeper sleeper;

    public StructuredOutputClient(
            ModelRouter router, PromptRenderer renderer, OutputParser parser, LlmCallRecorder recorder, Clock clock, Sleeper sleeper) {
        this.router = router;
        this.renderer = renderer;
        this.parser = parser;
        this.recorder = recorder;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * @throws LlmException               when the provider failed and no retry was possible or left (the last failure)
     * @throws StructuredOutputException  when the answers stayed invalid after the repair attempt
     */
    public <T> StructuredResult<T> generate(StructuredCall<T> call) {
        ModelRoute route = router.route(call.task());
        Instant deadline = clock.instant().plus(route.budget());
        PromptRenderer.RenderedPrompt prompt = renderer.render(call.prompt(), call.values());
        LlmRequest first = new LlmRequest(route.model(), prompt.system(), prompt.user(), route.options(), call.schema(), route.callTimeout());
        PromptEcho echo = PromptEcho.of(prompt);
        Operation operation = new Operation(call, route, deadline);
        List<String> problems = List.of();
        for (int attempt = 1; attempt <= MAX_STRUCTURED_ATTEMPTS; attempt++) {
            if (attempt > 1 && remaining(deadline).isZero()) {
                break;
            }
            LlmRequest request = attempt == 1 ? first : first.withUserPrompt(repairPrompt(prompt.user(), problems));
            Answered answered = operation.callWithRetries(request);
            try {
                T value = parser.parse(answered.response(), call.type(), echo::problems);
                List<String> semantic = call.check().apply(value);
                if (!semantic.isEmpty()) {
                    throw new OutputRejectedException(semantic);
                }
                UUID callId = operation.record(answered, attempt == 1 ? LlmCallStatus.OK : LlmCallStatus.REPAIRED, null);
                return new StructuredResult<>(value, route.model(), call.prompt().id(), call.prompt().version(), callId, operation.calls);
            } catch (OutputRejectedException rejected) {
                problems = rejected.problems();
                operation.record(answered, LlmCallStatus.INVALID_OUTPUT, String.join("; ", problems));
            }
        }
        throw new StructuredOutputException(call.task(), operation.structuredAttempts, problems);
    }

    /** The original task, followed by what was wrong with the previous answer. */
    static String repairPrompt(String user, List<String> problems) {
        StringBuilder text = new StringBuilder(user)
                .append("\n\nYour previous answer was rejected for these reasons:\n");
        problems.forEach(problem -> text.append("- ").append(problem).append('\n'));
        return text.append("Answer the same task again with one JSON object that follows the schema and fixes every problem above.")
                .toString();
    }

    private Duration remaining(Instant deadline) {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** The wait before retry number {@code retry} (0 for the first): what a rate limit asked for, else jittered exponential backoff. */
    public static Duration backoff(int retry, LlmException failure) {
        if (failure instanceof RateLimitedException limited && limited.retryAfter().isPresent()) {
            return limited.retryAfter().get();
        }
        long base = BACKOFF_INITIAL.toMillis() << Math.min(retry, 10);
        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble();
        return Duration.ofMillis(Math.min(BACKOFF_MAX.toMillis(), (long) (base * jitter)));
    }

    /** A provider answer and when its call started. */
    private record Answered(LlmResponse response, int callNumber) {
    }

    /** State of one structured operation: its route, deadline and the numbering of its calls. */
    private final class Operation {

        private final StructuredCall<?> call;
        private final ModelRoute route;
        private final Instant deadline;
        private int calls;
        private int structuredAttempts;

        Operation(StructuredCall<?> call, ModelRoute route, Instant deadline) {
            this.call = call;
            this.route = route;
            this.deadline = deadline;
        }

        Answered callWithRetries(LlmRequest request) {
            structuredAttempts++;
            for (int retry = 0; ; retry++) {
                Duration left = remaining(deadline);
                if (left.isZero()) {
                    throw new LlmTimeoutException(route.task() + " ran out of its call budget of " + route.budget());
                }
                Duration timeout = left.compareTo(route.callTimeout()) < 0 ? left : route.callTimeout();
                int callNumber = ++calls;
                Instant start = clock.instant();
                try {
                    return new Answered(route.provider().generate(request.withTimeout(timeout)), callNumber);
                } catch (LlmException failure) {
                    recordFailure(callNumber, Duration.between(start, clock.instant()), failure);
                    if (!failure.retryable() || retry >= route.maxRetries()) {
                        throw failure;
                    }
                    Duration wait = backoff(retry, failure);
                    if (remaining(deadline).compareTo(wait) <= 0) {
                        throw failure;
                    }
                    pause(wait);
                } catch (RuntimeException unexpected) {
                    recordFailure(callNumber, Duration.between(start, clock.instant()), unexpected);
                    throw unexpected;
                }
            }
        }

        private void pause(Duration wait) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ProviderUnavailableException("interrupted while waiting to retry " + route.task(), false, interrupted);
            }
        }

        @Nullable UUID record(Answered answered, LlmCallStatus status, @Nullable String error) {
            LlmResponse response = answered.response();
            return recorder.record(new LlmCallRecord(
                    call.interviewId(),
                    call.task().name(),
                    route.provider().id(),
                    route.model(),
                    response.modelVersion(),
                    call.prompt().id(),
                    call.prompt().version(),
                    answered.callNumber(),
                    status,
                    response.finishReason().name(),
                    response.usage().input(),
                    response.usage().output(),
                    response.usage().thinking(),
                    response.latency(),
                    status == LlmCallStatus.INVALID_OUTPUT ? "INVALID_OUTPUT" : null,
                    error,
                    call.contextChunkIds())).orElse(null);
        }

        private void recordFailure(int callNumber, Duration latency, RuntimeException failure) {
            LlmCallStatus status = switch (failure) {
                case RateLimitedException ignored -> LlmCallStatus.RATE_LIMITED;
                case LlmTimeoutException ignored -> LlmCallStatus.TIMEOUT;
                case ContentBlockedException ignored -> LlmCallStatus.BLOCKED;
                default -> LlmCallStatus.ERROR;
            };
            String code = failure instanceof LlmException llm ? llm.code() : failure.getClass().getSimpleName();
            recorder.record(new LlmCallRecord(
                    call.interviewId(),
                    call.task().name(),
                    route.provider().id(),
                    route.model(),
                    null,
                    call.prompt().id(),
                    call.prompt().version(),
                    callNumber,
                    status,
                    null,
                    null,
                    null,
                    null,
                    latency,
                    code,
                    failure.getMessage(),
                    call.contextChunkIds()));
        }
    }
}
