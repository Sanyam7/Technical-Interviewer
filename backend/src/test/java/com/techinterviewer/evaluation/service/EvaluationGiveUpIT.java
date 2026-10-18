package com.techinterviewer.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.support.Assessments;
import com.techinterviewer.evaluation.support.ReportTestBase;
import com.techinterviewer.evaluation.support.ScenarioBuilder;
import com.techinterviewer.evaluation.support.ScenarioBuilder.Asked;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.AnswerBlockedException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * The give-up policy for answers that cannot be evaluated (AI_ARCHITECTURE.md section 10.6) against the real database: three
 * attempts with a bounded backoff, a safety block that is final at once, the stored attempt counter and lease that make the limit
 * hold across concurrent requests and restarts, and the guarantee that a given-up answer is never scored.
 */
class EvaluationGiveUpIT extends ReportTestBase {

    private static final Duration TWO = Duration.ofSeconds(2);
    private static final Duration FOUR = Duration.ofSeconds(4);

    @Autowired
    EvaluationService service;

    private record One(ScenarioBuilder.Built built, Asked answer) {

        UUID interviewId() {
            return built.interviewId();
        }

        UUID answerId() {
            return answer.answerId();
        }
    }

    private One onePending() {
        ScenarioBuilder builder = newInterview();
        Asked asked = builder.technical("java/concurrency", 3, "an answer");
        return new One(builder.buildFinished(), asked);
    }

    private EvaluationStatus statusOf(One one) {
        return EvaluationStatus.valueOf(jdbc.queryForObject("select evaluation_status from answers where id = ?", String.class, one.answerId()));
    }

    private int attemptsOf(One one) {
        return jdbc.queryForObject("select evaluation_attempts from answers where id = ?", Integer.class, one.answerId());
    }

    private String failureOf(One one) {
        return jdbc.queryForObject("select evaluation_failure from answers where id = ?", String.class, one.answerId());
    }

    private Instant nextAttemptOf(One one) {
        Timestamp next = jdbc.queryForObject("select next_evaluation_at from answers where id = ?", Timestamp.class, one.answerId());
        return next == null ? null : next.toInstant();
    }

    private int evaluationRows(One one) {
        return count("evaluations", "answer_id", one.answerId());
    }

    private static AnswerAssessment wrongRubric() {
        return Assessments.builder(Evaluator.CODING).build();
    }

    private void failTheNext(int times) {
        for (int i = 0; i < times; i++) {
            evaluator.scriptNext(request -> {
                throw new AiUnavailableException("scripted outage");
            });
        }
    }

    // ---- retries ---------------------------------------------------------------------------------------------------

    @Test
    void aTransientFailureIsRetriedAfterTheBackoffAndTheSecondAttemptSucceeds() {
        One one = onePending();
        failTheNext(1);

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(1, 0, 0));
        assertThat(evaluator.calls()).isEqualTo(2);
        assertThat(pause.pauses()).containsExactly(TWO);
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(attemptsOf(one)).isEqualTo(2);
        assertThat(failureOf(one)).isNull();
        assertThat(nextAttemptOf(one)).isNull();
        assertThat(evaluationRows(one)).isEqualTo(1);
    }

    @Test
    void theBackoffDoublesAndTheThirdAttemptIsTheLastOneThatCanSucceed() {
        One one = onePending();
        failTheNext(2);

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(1, 0, 0));
        assertThat(pause.pauses()).containsExactly(TWO, FOUR);
        assertThat(attemptsOf(one)).isEqualTo(3);
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.COMPLETED);
    }

    @Test
    void afterThreeFailedAttemptsTheAnswerIsUnassessableAndNeverScored() {
        One one = onePending();
        failTheNext(3);

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(0, 0, 1));
        assertThat(evaluator.calls()).as("a fourth attempt never happens").isEqualTo(3);
        assertThat(pause.pauses()).containsExactly(TWO, FOUR);
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(failureOf(one)).isEqualTo("PROVIDER_FAILURE");
        assertThat(attemptsOf(one)).isEqualTo(3);
        assertThat(nextAttemptOf(one)).isNull();
        assertThat(evaluationRows(one)).as("no evaluation, in particular no score of zero").isZero();
    }

    @Test
    void aGivenUpAnswerIsFinalNothingIsAskedAndNothingChangesWhenTheEvaluatorIsBack() {
        One one = onePending();
        failTheNext(3);
        service.evaluatePending(one.interviewId());
        evaluator.reset();
        pause.reset();

        PendingEvaluationResult again = service.evaluatePending(one.interviewId());

        assertThat(again).isEqualTo(new PendingEvaluationResult(0, 0, 1));
        assertThat(evaluator.calls()).isZero();
        assertThat(pause.pauses()).isEmpty();
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(service.beginAttempt(one.interviewId(), one.answerId())).isEqualTo(new EvaluationAttempt.Settled(EvaluationStatus.UNASSESSABLE));
    }

    @Test
    void theReasonIsTheKindOfTheLastFailure() {
        One invalid = onePending();
        evaluator.scriptNext(request -> {
            throw new AiUnavailableException("outage");
        });
        evaluator.scriptNext(request -> {
            throw new AiUnavailableException("outage");
        });
        evaluator.scriptNext(request -> wrongRubric());

        service.evaluatePending(invalid.interviewId());

        assertThat(statusOf(invalid)).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(failureOf(invalid)).as("provider, provider, then unusable output").isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void anUnusableAssessmentIsAnInvalidOutputAttemptAndTheThirdOneGivesUp() {
        One one = onePending();
        evaluator.script(one.answerId(), request -> wrongRubric());

        service.evaluatePending(one.interviewId());

        assertThat(evaluator.calls()).isEqualTo(3);
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(failureOf(one)).isEqualTo("INVALID_OUTPUT");
        assertThat(evaluationRows(one)).isZero();
    }

    @Test
    void anAdapterThatCannotBuildItsResultIsAnInvalidOutputAndAnUnexpectedExceptionAProviderFailure() {
        One built = onePending();
        evaluator.script(built.answerId(), request -> {
            throw new IllegalArgumentException("rating must be between 0 and 4 but was 7");
        });
        One crashing = onePending();
        evaluator.script(crashing.answerId(), request -> {
            throw new IllegalStateException("adapter bug");
        });

        service.evaluatePending(built.interviewId());
        service.evaluatePending(crashing.interviewId());

        assertThat(failureOf(built)).isEqualTo("INVALID_OUTPUT");
        assertThat(failureOf(crashing)).isEqualTo("PROVIDER_FAILURE");
        assertThat(attemptsOf(built)).isEqualTo(3);
        assertThat(attemptsOf(crashing)).isEqualTo(3);
    }

    @Test
    void severalPendingAnswersShareTheWaitInsteadOfWaitingOneAfterTheOther() {
        ScenarioBuilder builder = newInterview();
        for (int i = 0; i < 5; i++) {
            builder.technical(i < 3 ? "java/concurrency" : "java/generics", 3, "answer " + i);
        }
        ScenarioBuilder.Built built = builder.buildFinished();
        evaluator.unavailable();

        PendingEvaluationResult result = service.evaluatePending(built.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(0, 0, 5));
        assertThat(evaluator.calls()).isEqualTo(15);
        assertThat(pause.pauses()).as("2 s and 4 s in all, for five answers").containsExactly(TWO, FOUR);
    }

    // ---- safety blocks ---------------------------------------------------------------------------------------------

    @Test
    void aSafetyBlockIsFinalAtOnceWithoutRetriesOrWaiting() {
        One one = onePending();
        evaluator.script(one.answerId(), request -> {
            throw new AnswerBlockedException("blocked by the provider's safety filter");
        });

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(0, 0, 1));
        assertThat(evaluator.calls()).isEqualTo(1);
        assertThat(pause.pauses()).isEmpty();
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(failureOf(one)).isEqualTo("SAFETY_BLOCK");
        assertThat(attemptsOf(one)).isEqualTo(1);
        assertThat(evaluationRows(one)).as("a refusal is not a verdict on the answer").isZero();
    }

    @Test
    void aSafetyBlockAfterTransientFailuresStillEndsTheAnswerAtOnceWithTheBlockAsItsReason() {
        One one = onePending();
        failTheNext(1);
        evaluator.scriptNext(request -> {
            throw new AnswerBlockedException("blocked");
        });

        service.evaluatePending(one.interviewId());

        assertThat(evaluator.calls()).isEqualTo(2);
        assertThat(failureOf(one)).isEqualTo("SAFETY_BLOCK");
        assertThat(attemptsOf(one)).isEqualTo(2);
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
    }

    @Test
    void aBlockedAnswerDoesNotStopTheOthersFromBeingEvaluated() {
        ScenarioBuilder builder = newInterview();
        Asked blocked = builder.technical("java/concurrency", 3, "first");
        Asked fine = builder.technical("java/generics", 3, "second");
        ScenarioBuilder.Built built = builder.buildFinished();
        evaluator.script(blocked.answerId(), request -> {
            throw new AnswerBlockedException("blocked");
        });

        PendingEvaluationResult result = service.evaluatePending(built.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(1, 0, 1));
        assertThat(count("evaluations", "answer_id", fine.answerId())).isEqualTo(1);
    }

    // ---- the stored attempt: backoff and lease -----------------------------------------------------------------------

    @Test
    void aFailedAttemptStoresItsBackoffAndNoAttemptMayStartBeforeItEnds() {
        One one = onePending();
        Instant now = clock.instant();
        EvaluationAttempt.Started first = (EvaluationAttempt.Started) service.beginAttempt(one.interviewId(), one.answerId());

        assertThat(first.number()).isEqualTo(1);
        assertThat(nextAttemptOf(one)).as("the claim lasts the lease").isEqualTo(now.plus(Duration.ofSeconds(90)));
        assertThat(service.recordFailure(one.interviewId(), one.answerId(), 1, EvaluationFailure.PROVIDER_FAILURE)).isEqualTo(EvaluationStatus.PENDING);
        assertThat(nextAttemptOf(one)).isEqualTo(now.plus(TWO));
        assertThat(failureOf(one)).isEqualTo("PROVIDER_FAILURE");

        assertThat(service.beginAttempt(one.interviewId(), one.answerId())).isEqualTo(new EvaluationAttempt.Waiting(TWO));
        clock.advance(Duration.ofSeconds(1));
        assertThat(service.beginAttempt(one.interviewId(), one.answerId())).isEqualTo(new EvaluationAttempt.Waiting(Duration.ofSeconds(1)));
        clock.advance(Duration.ofSeconds(1));
        EvaluationAttempt.Started second = (EvaluationAttempt.Started) service.beginAttempt(one.interviewId(), one.answerId());
        assertThat(second.number()).isEqualTo(2);
        assertThat(failureOf(one)).as("the failure of the attempt before is cleared while the next one runs").isNull();
        service.recordFailure(one.interviewId(), one.answerId(), 2, EvaluationFailure.PROVIDER_FAILURE);
        assertThat(nextAttemptOf(one)).as("the second backoff is twice the first").isEqualTo(clock.instant().plus(FOUR));
    }

    @Test
    void anAttemptInProgressBlocksEveryOtherCallerUntilItsLeaseEnds() {
        One one = onePending();
        service.beginAttempt(one.interviewId(), one.answerId());

        assertThat(service.beginAttempt(one.interviewId(), one.answerId())).isEqualTo(new EvaluationAttempt.Waiting(Duration.ofSeconds(90)));
        clock.advance(Duration.ofSeconds(89));
        assertThat(service.beginAttempt(one.interviewId(), one.answerId())).isEqualTo(new EvaluationAttempt.Waiting(Duration.ofSeconds(1)));
        clock.advance(Duration.ofSeconds(1));

        EvaluationAttempt.Started next = (EvaluationAttempt.Started) service.beginAttempt(one.interviewId(), one.answerId());
        assertThat(next.number()).as("the lost attempt counts, the next one is number 2").isEqualTo(2);
    }

    @Test
    void anAnswerWhoseNextAttemptIsFurtherAwayThanTheBackoffCapIsLeftPendingForALaterCall() {
        One one = onePending();
        service.beginAttempt(one.interviewId(), one.answerId());

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(0, 1, 0));
        assertThat(result.complete()).isFalse();
        assertThat(evaluator.calls()).isZero();
        assertThat(pause.pauses()).isEmpty();
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.PENDING);
    }

    @Test
    void aBackoffWithinTheCapIsWaitedOutByTheCaller() {
        One one = onePending();
        jdbc.update("update answers set evaluation_attempts = 1, evaluation_failure = 'PROVIDER_FAILURE', next_evaluation_at = ? where id = ?",
                Timestamp.from(clock.instant().plusSeconds(10)), one.answerId());

        service.evaluatePending(one.interviewId());

        assertThat(pause.pauses()).containsExactly(Duration.ofSeconds(10));
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(attemptsOf(one)).isEqualTo(2);
    }

    // ---- restart ---------------------------------------------------------------------------------------------------

    @Test
    void afterARestartTheAttemptsAlreadyMadeStillCount() {
        One one = onePending();
        // Two attempts happened before the process died; the second one failed and its backoff has passed.
        jdbc.update("update answers set evaluation_attempts = 2, evaluation_failure = 'PROVIDER_FAILURE', next_evaluation_at = ? where id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), one.answerId());
        failTheNext(5);

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(0, 0, 1));
        assertThat(evaluator.calls()).as("one attempt was left").isEqualTo(1);
        assertThat(attemptsOf(one)).isEqualTo(3);
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
    }

    @Test
    void anAttemptThatWasClaimedByAProcessThatDiedIsCountedWhenItsLeaseEnds() {
        One one = onePending();
        jdbc.update("update answers set evaluation_attempts = 1, next_evaluation_at = ? where id = ?",
                Timestamp.from(clock.instant().minusSeconds(5)), one.answerId());

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(1, 0, 0));
        assertThat(attemptsOf(one)).as("the lost attempt was number 1, this one is number 2").isEqualTo(2);
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.COMPLETED);
    }

    @Test
    void ifTheProcessDiedDuringTheLastAttemptTheAnswerIsGivenUpWithoutAskingAgain() {
        One one = onePending();
        jdbc.update("update answers set evaluation_attempts = 3, evaluation_failure = null, next_evaluation_at = ? where id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), one.answerId());

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(0, 0, 1));
        assertThat(evaluator.calls()).isZero();
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(failureOf(one)).isEqualTo("PROVIDER_FAILURE");
        assertThat(attemptsOf(one)).as("no attempt beyond the limit").isEqualTo(3);
    }

    @Test
    void ifTheProcessDiedDuringTheLastAttemptButItsLeaseIsStillRunningTheAnswerWaits() {
        One one = onePending();
        jdbc.update("update answers set evaluation_attempts = 3, next_evaluation_at = ? where id = ?",
                Timestamp.from(clock.instant().plusSeconds(60)), one.answerId());

        PendingEvaluationResult result = service.evaluatePending(one.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(0, 1, 0));
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.PENDING);
        clock.advance(Duration.ofSeconds(60));
        assertThat(service.evaluatePending(one.interviewId())).as("one lease later the answer is settled").isEqualTo(new PendingEvaluationResult(0, 0, 1));
    }

    // ---- idempotence and races ---------------------------------------------------------------------------------------

    @Test
    void reportingTheSameFailedAttemptTwiceChangesNothingTheSecondTime() {
        One one = onePending();
        service.beginAttempt(one.interviewId(), one.answerId());
        service.recordFailure(one.interviewId(), one.answerId(), 1, EvaluationFailure.PROVIDER_FAILURE);
        Instant retryAt = nextAttemptOf(one);
        clock.advance(Duration.ofSeconds(1));

        EvaluationStatus again = service.recordFailure(one.interviewId(), one.answerId(), 1, EvaluationFailure.INVALID_OUTPUT);

        assertThat(again).isEqualTo(EvaluationStatus.PENDING);
        assertThat(nextAttemptOf(one)).isEqualTo(retryAt);
        assertThat(failureOf(one)).isEqualTo("PROVIDER_FAILURE");
        assertThat(attemptsOf(one)).isEqualTo(1);
    }

    @Test
    void aReportForAnOlderAttemptOrForASettledAnswerIsIgnored() {
        One one = onePending();
        service.beginAttempt(one.interviewId(), one.answerId());
        service.recordFailure(one.interviewId(), one.answerId(), 1, EvaluationFailure.PROVIDER_FAILURE);
        clock.advance(TWO);
        service.beginAttempt(one.interviewId(), one.answerId());

        service.recordFailure(one.interviewId(), one.answerId(), 1, EvaluationFailure.PROVIDER_FAILURE);

        assertThat(attemptsOf(one)).isEqualTo(2);
        assertThat(failureOf(one)).as("attempt 2 is still in progress").isNull();
        service.recordFailure(one.interviewId(), one.answerId(), 2, EvaluationFailure.SAFETY_BLOCK);
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(service.recordFailure(one.interviewId(), one.answerId(), 2, EvaluationFailure.PROVIDER_FAILURE)).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(failureOf(one)).isEqualTo("SAFETY_BLOCK");
    }

    @Test
    void anEvaluationThatArrivesAfterTheAnswerWasGivenUpIsDiscarded() {
        One one = onePending();
        service.beginAttempt(one.interviewId(), one.answerId());
        service.recordFailure(one.interviewId(), one.answerId(), 1, EvaluationFailure.SAFETY_BLOCK);
        AnswerAssessment late = Assessments.uniform(Evaluator.TECHNICAL, 4, one.answer().expectedKeyPoints());

        assertThatThrownBy(() -> inTransaction(() -> {
            CandidateAnswer answer = answers.findById(one.answerId()).orElseThrow();
            InterviewQuestion question = questions.findById(one.answer().questionId()).orElseThrow();
            return service.record(answer, question, late);
        })).isInstanceOf(AnswerUnassessableException.class);

        assertThat(evaluationRows(one)).isZero();
        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.UNASSESSABLE);
    }

    @Test
    void anEvaluationForAnAnswerWhoseAttemptsFailedEarlierIsStoredAndClearsTheFailure() {
        One one = onePending();
        service.beginAttempt(one.interviewId(), one.answerId());
        service.recordFailure(one.interviewId(), one.answerId(), 1, EvaluationFailure.PROVIDER_FAILURE);
        clock.advance(TWO);
        service.beginAttempt(one.interviewId(), one.answerId());
        AnswerAssessment assessment = Assessments.uniform(Evaluator.TECHNICAL, 3, one.answer().expectedKeyPoints());

        inTransaction(() -> service.record(answers.findById(one.answerId()).orElseThrow(), questions.findById(one.answer().questionId()).orElseThrow(), assessment));

        assertThat(statusOf(one)).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(failureOf(one)).isNull();
        assertThat(evaluationRows(one)).isEqualTo(1);
    }

    @Test
    void manyCallersAtOnceNeverClaimTheSameAttemptTwice() throws Exception {
        One one = onePending();
        int callers = 8;
        CyclicBarrier lineUp = new CyclicBarrier(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<EvaluationAttempt> results = new ArrayList<>();
        try {
            List<Future<EvaluationAttempt>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    lineUp.await(30, TimeUnit.SECONDS);
                    return service.beginAttempt(one.interviewId(), one.answerId());
                }));
            }
            for (Future<EvaluationAttempt> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).filteredOn(result -> result instanceof EvaluationAttempt.Started).hasSize(1);
        assertThat(results).filteredOn(result -> result instanceof EvaluationAttempt.Waiting).hasSize(callers - 1);
        assertThat(attemptsOf(one)).isEqualTo(1);
    }

    @Test
    void manyConcurrentRunsOverAnOutageNeverExceedThreeAttemptsPerAnswer() throws Exception {
        ScenarioBuilder builder = newInterview();
        Asked first = builder.technical("java/concurrency", 3, "one");
        Asked second = builder.technical("java/generics", 3, "two");
        ScenarioBuilder.Built built = builder.buildFinished();
        evaluator.unavailable();
        int runs = 4;
        CyclicBarrier lineUp = new CyclicBarrier(runs);
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        try {
            List<Future<PendingEvaluationResult>> futures = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                futures.add(pool.submit(() -> {
                    lineUp.await(30, TimeUnit.SECONDS);
                    return service.evaluatePending(built.interviewId());
                }));
            }
            for (Future<PendingEvaluationResult> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Whichever runs claimed the attempts, the limit held. The recording pause moves the clock for everybody, so the
        // answers may already be settled or may still wait for a later call: finish them and look at the totals.
        service.evaluatePending(built.interviewId());
        for (Asked asked : List.of(first, second)) {
            int attempts = jdbc.queryForObject("select evaluation_attempts from answers where id = ?", Integer.class, asked.answerId());
            assertThat(attempts).isBetween(1, 3);
            assertThat(evaluator.callsFor(asked.answerId())).isLessThanOrEqualTo(3);
        }
        assertThat(evaluator.calls()).isLessThanOrEqualTo(6);
        assertThat(count("evaluations")).isZero();
    }

    // ---- guards ------------------------------------------------------------------------------------------------------

    @Test
    void aSkippedOrEvaluatedAnswerIsSettledAndNeverAttempted() {
        ScenarioBuilder builder = newInterview();
        Asked skipped = builder.technical("java/concurrency", 3, null);
        Asked evaluated = builder.technical("java/generics", 3, "an answer");
        ScenarioBuilder.Built built = builder.buildFinished();
        service.evaluatePending(built.interviewId());

        assertThat(service.beginAttempt(built.interviewId(), skipped.answerId())).isEqualTo(new EvaluationAttempt.Settled(EvaluationStatus.COMPLETED));
        assertThat(service.beginAttempt(built.interviewId(), evaluated.answerId())).isEqualTo(new EvaluationAttempt.Settled(EvaluationStatus.COMPLETED));
        assertThat(evaluator.calls()).isEqualTo(1);
    }

    @Test
    void anAnswerOfAnotherInterviewIsNotFoundForUpdate() {
        One mine = onePending();
        One other = onePending();

        assertThatThrownBy(() -> service.beginAttempt(mine.interviewId(), other.answerId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.recordFailure(mine.interviewId(), other.answerId(), 1, EvaluationFailure.PROVIDER_FAILURE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attemptsOf(other)).isZero();
    }

    @Test
    void theOperationsThatClaimOrReportRefuseToRunInsideATransactionAndTheInTransactionReportRequiresOne() {
        One one = onePending();

        assertThatThrownBy(() -> transaction.execute(status -> service.beginAttempt(one.interviewId(), one.answerId())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.execute(status -> service.recordFailure(one.interviewId(), one.answerId(), 1, EvaluationFailure.PROVIDER_FAILURE)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.failAttempt(answers.findById(one.answerId()).orElseThrow(), 1, EvaluationFailure.PROVIDER_FAILURE))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(attemptsOf(one)).isZero();
    }

    @Test
    void theTurnCanReportAnInvalidOutputInItsOwnTransaction() {
        One one = onePending();
        service.beginAttempt(one.interviewId(), one.answerId());

        EvaluationStatus status = inTransaction(() -> service.failAttempt(answers.findById(one.answerId()).orElseThrow(), 1, EvaluationFailure.INVALID_OUTPUT));

        assertThat(status).isEqualTo(EvaluationStatus.PENDING);
        assertThat(failureOf(one)).isEqualTo("INVALID_OUTPUT");
        assertThat(nextAttemptOf(one)).isEqualTo(clock.instant().plus(TWO));
    }

    @Test
    void theEvaluatorIsNeverCalledInsideATransaction() {
        One one = onePending();
        failTheNext(2);

        service.evaluatePending(one.interviewId());

        assertThat(evaluator.transactionViolations()).isEmpty();
    }

}
