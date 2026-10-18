package com.techinterviewer.evaluation.support;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.CodeAnalysis;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.AnswerEvaluator;
import com.techinterviewer.interview.port.CriterionRating;
import com.techinterviewer.interview.port.EvaluationRequest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A test double of the {@link AnswerEvaluator} port. Unless scripted it rates every answer {@code defaultRating} on
 * every criterion and covers every expected key point; with a {@link Quality} profile it judges like an evaluator of that
 * quality would, consistently with the key points of the question and the rubric of its evaluator. It records each
 * request, can be switched off, can fail after a number of calls, can return assessments that do not fit the question and
 * can hold one call until the test lets it go. It also records whether a database transaction was open when it was
 * called: the turn must never hold one across a port call.
 */
public final class ScriptedAnswerEvaluator implements AnswerEvaluator {

    private static final long GATE_TIMEOUT_SECONDS = 30;

    private final List<EvaluationRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<UUID, Function<EvaluationRequest, AnswerAssessment>> scripts = new ConcurrentHashMap<>();
    private final AtomicInteger delivered = new AtomicInteger();
    private volatile int defaultRating = 3;
    private volatile boolean available = true;
    private volatile int availableCalls = Integer.MAX_VALUE;
    private volatile @Nullable Gate gate;
    private volatile @Nullable Quality quality;
    private volatile @Nullable Function<EvaluationRequest, Quality> qualityBy;
    private final Queue<Function<EvaluationRequest, AnswerAssessment>> next = new ConcurrentLinkedQueue<>();
    private final List<String> transactionViolations = new CopyOnWriteArrayList<>();

    /** How good an answer is, as the evaluator judges it. The ratings are by position in the rubric's criteria. */
    public enum Quality {
        /** Every criterion 4 of 4 (score 100), every key point covered. */
        EXCELLENT(new int[] {4}),
        /** 3, 3, 3 and 4 (score about 75 for the technical rubric); the first key point covered, the others partly. */
        AVERAGE(new int[] {3, 3, 3, 4}),
        /** 2, 1, 2, 3 (score about 45); the first key point covered, the others missing. */
        PARTIAL(new int[] {2, 1, 2, 3}),
        /** 0, 0, 1, 1 (score under 20); every key point missing. */
        INCORRECT(new int[] {0, 0, 1, 1});

        private final int[] ratings;

        Quality(int[] ratings) {
            this.ratings = ratings;
        }

        int ratingAt(int criterionIndex) {
            return ratings[criterionIndex % ratings.length];
        }
    }

    /** Back to the defaults: available, rating 3, no scripts, no gate, no recorded requests. */
    public void reset() {
        requests.clear();
        scripts.clear();
        delivered.set(0);
        defaultRating = 3;
        available = true;
        availableCalls = Integer.MAX_VALUE;
        gate = null;
        quality = null;
        qualityBy = null;
        next.clear();
        transactionViolations.clear();
    }

    /** Every answer is judged as {@code quality} from now on. */
    public void quality(Quality quality) {
        this.quality = quality;
        this.qualityBy = null;
    }

    /** The quality of each answer is chosen from its request. */
    public void qualityBy(Function<EvaluationRequest, Quality> choice) {
        this.qualityBy = choice;
    }

    /** The next answers (one per call, in order) are judged as these qualities; afterwards the default applies again. */
    public void answerNext(Quality... qualities) {
        for (Quality each : qualities) {
            next.add(request -> assessment(request, each, false, List.of()));
        }
    }

    /** The next answer is judged as {@code quality} and flagged as an injection attempt. */
    public void flagNextAsInjection(Quality quality) {
        next.add(request -> assessment(request, quality, true, List.of()));
    }

    /** The next answer is judged as {@code quality} and the candidate is found to claim these things about their experience. */
    public void claimNext(Quality quality, List<String> claims) {
        next.add(request -> assessment(request, quality, false, claims));
    }

    /** The next call returns what {@code script} makes of the request (an assessment that does not fit, for instance). */
    public void scriptNext(Function<EvaluationRequest, AnswerAssessment> script) {
        next.add(script);
    }

    /** A database transaction (or its synchronisation) was open during a call: each entry names the call. Must stay empty. */
    public List<String> transactionViolations() {
        return List.copyOf(transactionViolations);
    }

    /** The assessment an evaluator of this quality gives, valid for the question of the request. */
    public static AnswerAssessment assessment(EvaluationRequest request, Quality quality, boolean injection, List<String> claims) {
        Map<Criterion, CriterionRating> ratings = new EnumMap<>(Criterion.class);
        List<Criterion> criteria = new ArrayList<>(RubricRegistry.forEvaluator(request.evaluator()).criteria());
        // A design dimension rests on the candidate's words (the agent has verified them by the time they reach the port).
        String quote = request.answerText().strip().length() > 60 ? request.answerText().strip().substring(0, 60).strip() : request.answerText().strip();
        for (int i = 0; i < criteria.size(); i++) {
            int rating = quality.ratingAt(i);
            List<String> evidence = request.evaluator() == Evaluator.SYSTEM_DESIGN && rating > 0 ? List.of(quote) : List.of();
            ratings.put(criteria.get(i), new CriterionRating(rating, "Scripted " + quality + " " + criteria.get(i), evidence));
        }
        List<KeyPointAssessment> keyPoints = new ArrayList<>();
        List<ExpectedKeyPoint> expected = request.expectedKeyPoints();
        for (int i = 0; i < expected.size(); i++) {
            KeyPointStatus status = switch (quality) {
                case EXCELLENT -> KeyPointStatus.COVERED;
                case AVERAGE -> i == 0 ? KeyPointStatus.COVERED : KeyPointStatus.PARTIAL;
                case PARTIAL -> i == 0 ? KeyPointStatus.COVERED : KeyPointStatus.MISSING;
                case INCORRECT -> KeyPointStatus.MISSING;
            };
            boolean shown = status != KeyPointStatus.MISSING;
            keyPoints.add(new KeyPointAssessment(expected.get(i).id(), status, shown ? "quoted from the answer" : null, shown));
        }
        Set<EvaluationFlag> flags = injection ? EnumSet.of(EvaluationFlag.INJECTION_SUSPECTED) : EnumSet.noneOf(EvaluationFlag.class);
        String about = request.questionPrompt().length() > 60 ? request.questionPrompt().substring(0, 60) : request.questionPrompt();
        return new AnswerAssessment(
                ratings, keyPoints, List.of(), claims, "A " + quality + " answer to: " + about, flags,
                "scripted-evaluation", "1.0", "scripted-model", null, null,
                request.evaluator() == Evaluator.CODING ? new CodeAnalysis("Scripted " + quality + " approach", "O(n)", "O(n)") : null);
    }

    public void rateAll(int rating) {
        defaultRating = rating;
    }

    public void script(UUID answerId, Function<EvaluationRequest, AnswerAssessment> script) {
        scripts.put(answerId, script);
    }

    public void unavailable() {
        available = false;
    }

    public void available() {
        available = true;
    }

    /** After {@code calls} answers were assessed, every further call fails as unavailable. */
    public void unavailableAfter(int calls) {
        availableCalls = calls;
    }

    /** The next call announces itself and then waits until {@link Gate#release()}. */
    public Gate blockNextCall() {
        Gate next = new Gate();
        gate = next;
        return next;
    }

    /** Every request received so far, in order. */
    public List<EvaluationRequest> requests() {
        return List.copyOf(requests);
    }

    public int calls() {
        return requests.size();
    }

    /** How many times the evaluator was asked about this answer. */
    public long callsFor(UUID answerId) {
        return requests.stream().filter(request -> request.answerId().equals(answerId)).count();
    }

    @Override
    public AnswerAssessment evaluate(EvaluationRequest request) {
        requests.add(request);
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) {
            transactionViolations.add("evaluate(" + request.answerId() + ")");
        }
        Gate held = gate;
        if (held != null && held.claim()) {
            held.await();
        }
        if (!available || delivered.get() >= availableCalls) {
            throw new AiUnavailableException("scripted outage");
        }
        delivered.incrementAndGet();
        Function<EvaluationRequest, AnswerAssessment> script = scripts.get(request.answerId());
        if (script != null) {
            return script.apply(request);
        }
        Function<EvaluationRequest, AnswerAssessment> queued = next.poll();
        if (queued != null) {
            return queued.apply(request);
        }
        Function<EvaluationRequest, Quality> choice = qualityBy;
        Quality profile = choice != null ? choice.apply(request) : quality;
        if (profile != null) {
            return assessment(request, profile, false, List.of());
        }
        return Assessments.uniform(request.evaluator(), defaultRating, request.expectedKeyPoints());
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
                    throw new IllegalStateException("the test never released the evaluator");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        /** Waits until a call is being held. */
        public void awaitEntered() throws InterruptedException {
            if (!entered.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("no evaluator call arrived");
            }
        }

        public void release() {
            released.countDown();
        }
    }
}
