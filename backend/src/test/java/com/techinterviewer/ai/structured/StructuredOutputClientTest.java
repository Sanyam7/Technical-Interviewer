package com.techinterviewer.ai.structured;

import static com.techinterviewer.ai.observability.LlmCallStatus.BLOCKED;
import static com.techinterviewer.ai.observability.LlmCallStatus.ERROR;
import static com.techinterviewer.ai.observability.LlmCallStatus.INVALID_OUTPUT;
import static com.techinterviewer.ai.observability.LlmCallStatus.OK;
import static com.techinterviewer.ai.observability.LlmCallStatus.RATE_LIMITED;
import static com.techinterviewer.ai.observability.LlmCallStatus.REPAIRED;
import static com.techinterviewer.ai.observability.LlmCallStatus.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.ai.observability.LlmCallRecord;
import com.techinterviewer.ai.prompt.PromptTemplate;
import com.techinterviewer.ai.prompt.PromptValue;
import com.techinterviewer.ai.provider.ContentBlockedException;
import com.techinterviewer.ai.provider.InvalidRequestException;
import com.techinterviewer.ai.provider.LlmRequest;
import com.techinterviewer.ai.provider.LlmTimeoutException;
import com.techinterviewer.ai.provider.ProviderUnavailableException;
import com.techinterviewer.ai.provider.RateLimitedException;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.ai.support.ScriptedLlmProvider;
import com.techinterviewer.TestJson;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The orchestration of one structured operation: retries, repair, call budget and call records. */
class StructuredOutputClientTest {

    /** A minimal output type. */
    record Verdict(@NotBlank String verdict) {
    }

    private static final PromptTemplate PROMPT = new PromptTemplate("test-prompt", "v3", "test.v1",
            "You judge.", "Judge this: {{thing}}");

    private static final UUID INTERVIEW = UUID.randomUUID();

    private static StructuredCall<Verdict> call(java.util.function.Function<Verdict, List<String>> check) {
        return new StructuredCall<>(AiTask.ANSWER_EVALUATION, PROMPT, Map.of("thing", PromptValue.untrusted("thing", "an answer")),
                TestJson.applicationMapper().readTree("{\"type\":\"object\"}"), Verdict.class, check, INTERVIEW);
    }

    private static StructuredCall<Verdict> call() {
        return call(verdict -> List.of());
    }

    @Test
    void aValidFirstAnswerIsReturnedWithItsProvenanceAndRecordedAsOk() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider().thenAnswer("{\"verdict\":\"good\"}");
        AiStack stack = AiStack.around(provider);

        StructuredResult<Verdict> result = stack.client.generate(call());

        assertThat(result.value()).isEqualTo(new Verdict("good"));
        assertThat(result.promptId()).isEqualTo("test-prompt");
        assertThat(result.promptVersion()).isEqualTo("v3");
        assertThat(result.model()).isEqualTo("test-model");
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(result.llmCallId()).isEqualTo(stack.recorder.idOf(0));
        LlmCallRecord record = stack.recorder.records().getFirst();
        assertThat(record.status()).isEqualTo(OK);
        assertThat(record.interviewId()).isEqualTo(INTERVIEW);
        assertThat(record.task()).isEqualTo("ANSWER_EVALUATION");
        assertThat(record.provider()).isEqualTo("gemini");
        assertThat(record.promptId()).isEqualTo("test-prompt");
        assertThat(record.promptVersion()).isEqualTo("v3");
        assertThat(record.attempt()).isEqualTo(1);
        assertThat(record.finishReason()).isEqualTo("STOP");
        assertThat(record.inputTokens()).isEqualTo(120);
        assertThat(record.modelVersion()).isEqualTo("scripted-001");
    }

    @Test
    void theRenderedPromptAndTheSchemaReachTheProvider() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider().thenAnswer("{\"verdict\":\"good\"}");

        AiStack.around(provider).client.generate(call());

        LlmRequest sent = provider.lastRequest();
        assertThat(sent.systemPrompt()).isEqualTo("You judge.");
        assertThat(sent.userPrompt()).startsWith("Judge this: [BEGIN UNTRUSTED thing ").contains("an answer");
        assertThat(sent.responseSchema().get("type").asString()).isEqualTo("object");
        assertThat(sent.model()).isEqualTo("test-model");
    }

    @Test
    void anInvalidAnswerIsRepairedOnceWithTheProblemsShownToTheModel() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider()
                .thenAnswer("{\"verdict\":\"\"}")
                .thenAnswer("{\"verdict\":\"fixed\"}");
        AiStack stack = AiStack.around(provider);

        StructuredResult<Verdict> result = stack.client.generate(call());

        assertThat(result.value().verdict()).isEqualTo("fixed");
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(result.llmCallId()).isEqualTo(stack.recorder.idOf(1));
        assertThat(stack.recorder.statuses()).containsExactly(INVALID_OUTPUT, REPAIRED);
        assertThat(stack.recorder.records().getFirst().errorMessage()).contains("verdict");
        String repair = provider.requests().get(1).userPrompt();
        assertThat(repair).startsWith(provider.requests().get(0).userPrompt())
                .contains("Your previous answer was rejected").contains("- verdict: must not be blank");
        assertThat(provider.requests().get(1).systemPrompt()).isEqualTo("You judge.");
    }

    @Test
    void aSemanticProblemIsRepairedLikeASchemaProblem() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider()
                .thenAnswer("{\"verdict\":\"bad\"}")
                .thenAnswer("{\"verdict\":\"good\"}");
        AiStack stack = AiStack.around(provider);

        StructuredResult<Verdict> result = stack.client.generate(
                call(verdict -> verdict.verdict().equals("good") ? List.of() : List.of("verdict: must be good")));

        assertThat(result.value().verdict()).isEqualTo("good");
        assertThat(provider.lastRequest().userPrompt()).contains("- verdict: must be good");
        assertThat(stack.recorder.statuses()).containsExactly(INVALID_OUTPUT, REPAIRED);
    }

    @Test
    void anAnswerStillInvalidAfterTheRepairFailsWithTheLastProblems() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider().always(request -> "not json at all");
        AiStack stack = AiStack.around(provider);

        assertThatThrownBy(() -> stack.client.generate(call()))
                .isInstanceOfSatisfying(StructuredOutputException.class, failure -> {
                    assertThat(failure.task()).isEqualTo(AiTask.ANSWER_EVALUATION);
                    assertThat(failure.problems()).containsExactly("the answer contains no JSON object");
                });
        assertThat(provider.calls()).isEqualTo(StructuredOutputClient.MAX_STRUCTURED_ATTEMPTS);
        assertThat(stack.recorder.statuses()).containsExactly(INVALID_OUTPUT, INVALID_OUTPUT);
    }

    @Test
    void aRateLimitIsRetriedAfterTheTimeTheProviderAskedFor() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider()
                .thenFail(new RateLimitedException("429", Duration.ofSeconds(3)))
                .thenAnswer("{\"verdict\":\"good\"}");
        AiStack stack = AiStack.around(provider);

        StructuredResult<Verdict> result = stack.client.generate(call());

        assertThat(result.value().verdict()).isEqualTo("good");
        assertThat(stack.sleeps).containsExactly(Duration.ofSeconds(3));
        assertThat(stack.recorder.statuses()).containsExactly(RATE_LIMITED, OK);
        assertThat(stack.recorder.records().stream().map(LlmCallRecord::attempt)).containsExactly(1, 2);
        assertThat(stack.recorder.records().getFirst().errorCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void anOutageIsRetriedWithGrowingJitteredBackoffUpToTheProvidersRetryLimit() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider().alwaysFail(new ProviderUnavailableException("503", true));
        AiStack stack = AiStack.around(provider);

        assertThatThrownBy(() -> stack.client.generate(call())).isInstanceOf(ProviderUnavailableException.class);

        assertThat(provider.calls()).isEqualTo(3);
        assertThat(stack.recorder.statuses()).containsExactly(ERROR, ERROR, ERROR);
        assertThat(stack.sleeps).hasSize(2);
        assertThat(stack.sleeps.get(0)).isBetween(Duration.ofMillis(250), Duration.ofMillis(750));
        assertThat(stack.sleeps.get(1)).isBetween(Duration.ofMillis(500), Duration.ofMillis(1500));
    }

    @Test
    void aRejectedRequestOrBlockedContentIsNeverRetried() {
        ScriptedLlmProvider rejecting = new ScriptedLlmProvider().alwaysFail(new InvalidRequestException("400 bad model"));
        AiStack first = AiStack.around(rejecting);
        assertThatThrownBy(() -> first.client.generate(call())).isInstanceOf(InvalidRequestException.class);
        assertThat(rejecting.calls()).isEqualTo(1);
        assertThat(first.recorder.statuses()).containsExactly(ERROR);

        ScriptedLlmProvider blocking = new ScriptedLlmProvider().alwaysFail(new ContentBlockedException("SAFETY"));
        AiStack second = AiStack.around(blocking);
        assertThatThrownBy(() -> second.client.generate(call())).isInstanceOf(ContentBlockedException.class);
        assertThat(blocking.calls()).isEqualTo(1);
        assertThat(second.recorder.statuses()).containsExactly(BLOCKED);
        assertThat(second.sleeps).isEmpty();
    }

    @Test
    void aRetryGetsOnlyWhatIsLeftOfTheBudget() {
        AiStack[] stack = new AiStack[1];
        ScriptedLlmProvider provider = new ScriptedLlmProvider()
                .thenAnswer(request -> {
                    stack[0].clock.advance(Duration.ofSeconds(25)); // the first call hangs for 25 s, then times out
                    throw new LlmTimeoutException("slow");
                })
                .thenAnswer("{\"verdict\":\"good\"}");
        stack[0] = AiStack.around(provider, Duration.ofSeconds(30), 2, Duration.ofSeconds(40));

        stack[0].client.generate(call());

        assertThat(provider.requests().get(0).timeout()).isEqualTo(Duration.ofSeconds(30));
        Duration waited = stack[0].sleeps.getFirst();
        assertThat(provider.requests().get(1).timeout()).isEqualTo(Duration.ofSeconds(15).minus(waited));
        assertThat(stack[0].recorder.statuses()).containsExactly(TIMEOUT, OK);
    }

    @Test
    void theBudgetBoundsTheWholeOperationRetriesAndRepairIncluded() {
        AiStack[] stack = new AiStack[1];
        ScriptedLlmProvider provider = new ScriptedLlmProvider().always(request -> {
            stack[0].clock.advance(Duration.ofSeconds(25)); // each call takes 25 s
            return "{\"verdict\":\"\"}"; // and is invalid
        });
        stack[0] = AiStack.around(provider, Duration.ofSeconds(30), 2, Duration.ofSeconds(40));

        assertThatThrownBy(() -> stack[0].client.generate(call())).isInstanceOf(StructuredOutputException.class);

        // The repair attempt got only the 15 s that were left.
        assertThat(provider.requests().get(0).timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(provider.requests().get(1).timeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void noRepairStartsWhenTheBudgetIsSpent() {
        AiStack[] stack = new AiStack[1];
        ScriptedLlmProvider provider = new ScriptedLlmProvider().always(request -> {
            stack[0].clock.advance(Duration.ofSeconds(45));
            return "{\"verdict\":\"\"}";
        });
        stack[0] = AiStack.around(provider, Duration.ofSeconds(30), 2, Duration.ofSeconds(40));

        assertThatThrownBy(() -> stack[0].client.generate(call())).isInstanceOf(StructuredOutputException.class);

        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void aRetryWhoseWaitWouldOutlastTheBudgetIsNotAttempted() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider()
                .thenFail(new RateLimitedException("429", Duration.ofSeconds(120)))
                .thenAnswer("{\"verdict\":\"good\"}");
        AiStack stack = AiStack.around(provider, Duration.ofSeconds(30), 2, Duration.ofSeconds(60));

        assertThatThrownBy(() -> stack.client.generate(call())).isInstanceOf(RateLimitedException.class);

        assertThat(provider.calls()).isEqualTo(1);
        assertThat(stack.sleeps).isEmpty();
    }

    @Test
    void retriesStopWhenTheBudgetIsSpentEvenIfTheProviderAllowsMore() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider().alwaysFail(new ProviderUnavailableException("down", true));
        AiStack stack = AiStack.around(provider, Duration.ofSeconds(30), 5, Duration.ofSeconds(2));

        assertThatThrownBy(() -> stack.client.generate(call())).isInstanceOf(ProviderUnavailableException.class);

        assertThat(provider.calls()).isLessThan(6);
        Duration waited = stack.sleeps.stream().reduce(Duration.ZERO, Duration::plus);
        assertThat(waited).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void anInterruptedWaitStopsTheOperationAndKeepsTheInterruptFlag() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider().alwaysFail(new ProviderUnavailableException("503", true));
        AiStack stack = AiStack.around(provider);
        StructuredOutputClient interrupted = new StructuredOutputClient(stack.router, new com.techinterviewer.ai.prompt.PromptRenderer(),
                new OutputParser(TestJson.applicationMapper(), AiStack.validator()), stack.recorder, stack.clock,
                duration -> {
                    throw new InterruptedException();
                });

        try {
            assertThatThrownBy(() -> interrupted.generate(call()))
                    .isInstanceOfSatisfying(ProviderUnavailableException.class, failure -> assertThat(failure.retryable()).isFalse());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anUnexpectedProviderErrorIsRecordedAndPassedOn() {
        ScriptedLlmProvider broken = new ScriptedLlmProvider().thenAnswer(request -> {
            throw new IllegalStateException("bug in an adapter");
        });
        AiStack stack = AiStack.around(broken);

        assertThatThrownBy(() -> stack.client.generate(call())).isInstanceOf(IllegalStateException.class);

        assertThat(stack.recorder.statuses()).containsExactly(ERROR);
        assertThat(stack.recorder.records().getFirst().errorCode()).isEqualTo("IllegalStateException");
    }

    @Test
    void aFailedRecordingLeavesTheResultWithoutACallId() {
        ScriptedLlmProvider provider = new ScriptedLlmProvider().thenAnswer("{\"verdict\":\"good\"}");
        AiStack stack = AiStack.around(provider);
        StructuredOutputClient unrecorded = new StructuredOutputClient(stack.router, new com.techinterviewer.ai.prompt.PromptRenderer(),
                new OutputParser(TestJson.applicationMapper(), AiStack.validator()),
                new com.techinterviewer.ai.observability.LlmCallRecorder(null, null, null) {
                    @Override
                    public java.util.Optional<UUID> record(LlmCallRecord record) {
                        return java.util.Optional.empty();
                    }
                }, stack.clock, duration -> { });

        assertThat(unrecorded.generate(call()).llmCallId()).isNull();
    }
}
