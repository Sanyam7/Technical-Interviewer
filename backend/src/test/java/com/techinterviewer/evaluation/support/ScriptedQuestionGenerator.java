package com.techinterviewer.evaluation.support;

import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.port.QuestionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A test double of the {@link QuestionGenerator} port: returns a valid, distinct question for every request (key points
 * that fit the kind of question, coding material for a coding problem, the topic and difficulty it was asked for) and
 * records every request it receives, so a test can see what the interview asked the generator for. It can be switched off,
 * made to fail unexpectedly or to answer for the wrong topic, to hold a call until the test lets it go, and it records
 * whether a database transaction was open during a call (the turn must never hold one across a port call).
 */
public final class ScriptedQuestionGenerator implements QuestionGenerator {

    private static final long GATE_TIMEOUT_SECONDS = 30;

    private final List<QuestionRequest> requests = new CopyOnWriteArrayList<>();
    private final List<String> transactionViolations = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile boolean available = true;
    private volatile int availableCalls = Integer.MAX_VALUE;
    private volatile boolean wrongTopic;
    private volatile @Nullable RuntimeException failure;
    private volatile @Nullable Function<QuestionRequest, GeneratedQuestion> script;
    private volatile @Nullable Gate gate;
    private final AtomicInteger delivered = new AtomicInteger();

    public void reset() {
        requests.clear();
        transactionViolations.clear();
        counter.set(0);
        delivered.set(0);
        available = true;
        availableCalls = Integer.MAX_VALUE;
        wrongTopic = false;
        failure = null;
        script = null;
        gate = null;
    }

    public void unavailable() {
        available = false;
    }

    public void available() {
        available = true;
    }

    /** After {@code calls} questions were delivered every further call fails as unavailable. */
    public void unavailableAfter(int calls) {
        availableCalls = calls;
    }

    /** Every call throws {@code failure}, which is not an {@link AiUnavailableException}. */
    public void failWith(@Nullable RuntimeException failure) {
        this.failure = failure;
    }

    /** Questions with a topic come back for another topic than the one asked for: a result the interview cannot use. */
    public void answerForAnotherTopic(boolean wrongTopic) {
        this.wrongTopic = wrongTopic;
    }

    public void script(@Nullable Function<QuestionRequest, GeneratedQuestion> script) {
        this.script = script;
    }

    /** The next call announces itself and then waits until {@link Gate#release()}. */
    public Gate blockNextCall() {
        Gate next = new Gate();
        gate = next;
        return next;
    }

    public List<QuestionRequest> requests() {
        return List.copyOf(requests);
    }

    public int calls() {
        return requests.size();
    }

    public QuestionRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    /** A database transaction (or its synchronisation) was open during a call: each entry names the call. Must stay empty. */
    public List<String> transactionViolations() {
        return List.copyOf(transactionViolations);
    }

    @Override
    public GeneratedQuestion generate(QuestionRequest request) {
        requests.add(request);
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) {
            transactionViolations.add("generate(" + request.kind() + ")");
        }
        Gate held = gate;
        if (held != null && held.claim()) {
            held.await();
        }
        RuntimeException broken = failure;
        if (broken != null) {
            throw broken;
        }
        if (!available || delivered.get() >= availableCalls) {
            throw new AiUnavailableException("scripted outage");
        }
        delivered.incrementAndGet();
        Function<QuestionRequest, GeneratedQuestion> scripted = script;
        if (scripted != null) {
            return scripted.apply(request);
        }
        return standard(request, counter.incrementAndGet());
    }

    private GeneratedQuestion standard(QuestionRequest request, int number) {
        String topicKey = request.topic() == null ? null : request.topic().key();
        if (wrongTopic && topicKey != null) {
            topicKey = "not-a-skill/not-a-topic";
        }
        String subject = request.topic() == null ? request.section().name().toLowerCase().replace('_', ' ') : request.topic().topicName();
        String prompt = "Scripted " + request.kind() + " question " + number + " about " + subject + " at level " + request.targetDifficulty()
                + (request.followUp() == null ? "" : " (" + request.followUp().mode()
                        + (request.followUp().designProbe() == null ? "" : " " + request.followUp().designProbe().criterion())
                        + " after: " + request.followUp().parentQuestionPrompt() + ")");
        return new GeneratedQuestion(
                prompt.length() > GeneratedQuestion.MAX_PROMPT_LENGTH ? prompt.substring(0, GeneratedQuestion.MAX_PROMPT_LENGTH) : prompt,
                keyPointsOf(request.kind(), number),
                request.kind() == QuestionKind.CODING ? codingDetails(request) : null,
                QuestionOrigin.GENERATED,
                request.targetDifficulty(),
                topicKey,
                request.kind() == QuestionKind.FOLLOW_UP ? 2 : 4,
                List.of(),
                "scripted-question",
                "1.0",
                "scripted-model",
                null);
    }

    private static List<ExpectedKeyPoint> keyPointsOf(QuestionKind kind, int number) {
        int count = switch (kind) {
            case FOLLOW_UP -> 2;
            case SYSTEM_DESIGN -> 4;
            default -> 3;
        };
        List<ExpectedKeyPoint> keyPoints = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            keyPoints.add(new ExpectedKeyPoint("kp" + i, "Key point " + i + " of question " + number, List.of()));
        }
        return keyPoints;
    }

    private static QuestionDetails codingDetails(QuestionRequest request) {
        return new QuestionDetails(
                List.of("1 <= n <= 100000"),
                List.of(new CodingExample("[1, 2, 3]", "6", "1 + 2 + 3")),
                "int sum(int[] values)",
                request.codingLanguage());
    }

    /** Holds the first call that reaches it. */
    public static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger claims = new AtomicInteger();

        private boolean claim() {
            return claims.getAndIncrement() == 0;
        }

        private void await() {
            entered.countDown();
            try {
                if (!released.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the generator");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        public void awaitEntered() throws InterruptedException {
            if (!entered.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("no generator call arrived");
            }
        }

        public void release() {
            released.countDown();
        }
    }
}
