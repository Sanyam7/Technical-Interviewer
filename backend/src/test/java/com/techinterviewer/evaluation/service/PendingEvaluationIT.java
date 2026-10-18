package com.techinterviewer.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.support.Assessments;
import com.techinterviewer.evaluation.support.ReportTestBase;
import com.techinterviewer.evaluation.support.ScenarioBuilder.Asked;
import com.techinterviewer.evaluation.support.ScenarioBuilder;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.Misconception;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.EvaluationRequest;
import com.techinterviewer.persistence.Fixtures;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Evaluating the answers that are still pending: ports outside transactions, one short transaction per result. */
class PendingEvaluationIT extends ReportTestBase {

    @Autowired
    EvaluationService service;

    private BigDecimal scoreOf(Asked asked) {
        return jdbc.queryForObject("select overall_score from evaluations where answer_id = ?", BigDecimal.class, asked.answerId());
    }

    private EvaluationStatus statusOf(Asked asked) {
        return EvaluationStatus.valueOf(jdbc.queryForObject("select evaluation_status from answers where id = ?", String.class, asked.answerId()));
    }

    private int evaluationRows(UUID interviewId) {
        return jdbc.queryForObject(
                "select count(*) from evaluations e join answers a on a.id = e.answer_id where a.interview_id = ?", Integer.class, interviewId);
    }

    private record Three(ScenarioBuilder.Built built, Asked first, Asked second, Asked third) {
    }

    private Three threePending() {
        ScenarioBuilder builder = newInterview();
        Asked first = builder.technical("java/concurrency", 3, "first answer");
        Asked second = builder.technical("java/generics", 3, "second answer");
        Asked third = builder.technical("java/exceptions", 2, "third answer");
        return new Three(builder.buildFinished(), first, second, third);
    }

    @Test
    void everyPendingAnswerIsEvaluatedStoredAndMarkedCompletedAndSkippedAnswersAreNeverSent() {
        ScenarioBuilder builder = newInterview();
        Asked intro = builder.intro("I am a backend engineer.");
        Asked technical = builder.technical("java/concurrency", 3, "volatile gives visibility");
        Asked skipped = builder.technical("java/generics", 3, null);
        Asked coding = builder.coding(3, "int solve(int n) { return n; }");
        ScenarioBuilder.Built built = builder.buildFinished();
        evaluator.rateAll(4);

        PendingEvaluationResult result = service.evaluatePending(built.interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(3, 0));
        assertThat(result.complete()).isTrue();
        assertThat(evaluator.requests()).extracting(EvaluationRequest::answerId)
                .containsExactly(intro.answerId(), technical.answerId(), coding.answerId());
        assertThat(evaluationRows(built.interviewId())).isEqualTo(3);
        assertThat(count("evaluations", "answer_id", skipped.answerId())).isZero();
        assertThat(scoreOf(intro)).isEqualByComparingTo("100");
        assertThat(scoreOf(technical)).isEqualByComparingTo("100");
        // The coding problem is not from the bank, so its solution was not tested: correctness is capped at 3 (NO_TEST_EVIDENCE).
        assertThat(scoreOf(coding)).isEqualByComparingTo("91.25");
        for (Asked asked : List.of(intro, technical, coding, skipped)) {
            assertThat(statusOf(asked)).isEqualTo(EvaluationStatus.COMPLETED);
        }
    }

    @Test
    void theRequestCarriesTheQuestionItsKeyPointsTheAnswerTheThreadAndTheMemory() {
        ScenarioBuilder builder = newInterview();
        Asked root = builder.technical("spring-boot/transactions", 4, "Transactions are proxies.");
        Asked followUp = builder.followUp(root, Section.QUESTIONING, "spring-boot/transactions", 4, "Self-invocation skips the proxy.");
        Asked codingRoot = builder.coding(3, "int f() { return 1; }");
        Asked codingFollowUp = builder.followUp(codingRoot, Section.CODING, null, 3, "It is constant time.");
        ScenarioBuilder.Built built = builder.buildFinished();
        UUID claimSource = UUID.randomUUID();
        inTransaction(() -> {
            Interview interview = interviews.findById(built.interviewId()).orElseThrow();
            interview.replaceMemory(new InterviewMemory(Map.of(), List.of(), List.of(new Claim("Ran Kafka in production", false, claimSource)),
                    List.of(new AnswerSummary(root.questionId(), "spring-boot/transactions", "Knows proxies.", 80)), Map.of(), Map.of(), null),
                    ScenarioBuilder.START);
            interviews.saveAndFlush(interview);
        });
        jdbc.update("update answers set evaluation_status = 'COMPLETED' where id = ?", root.answerId());
        jdbc.update("update answers set evaluation_status = 'COMPLETED' where id = ?", codingRoot.answerId());

        service.evaluatePending(built.interviewId());

        EvaluationRequest followUpRequest = evaluator.requests().stream()
                .filter(request -> request.answerId().equals(followUp.answerId())).findFirst().orElseThrow();
        assertThat(followUpRequest.interviewId()).isEqualTo(built.interviewId());
        assertThat(followUpRequest.evaluator()).isEqualTo(Evaluator.TECHNICAL);
        assertThat(followUpRequest.topic().key()).isEqualTo("spring-boot/transactions");
        assertThat(followUpRequest.topic().skillName()).isEqualTo("Spring Boot");
        assertThat(followUpRequest.difficulty()).isEqualTo(4);
        assertThat(followUpRequest.questionPrompt()).startsWith("Question number 2");
        assertThat(followUpRequest.expectedKeyPoints()).extracting(keyPoint -> keyPoint.id()).containsExactly("kp1", "kp2");
        assertThat(followUpRequest.answerText()).isEqualTo("Self-invocation skips the proxy.");
        assertThat(followUpRequest.thread()).hasSize(1);
        assertThat(followUpRequest.thread().get(0).questionPrompt()).startsWith("Question number 1");
        assertThat(followUpRequest.thread().get(0).answerText()).isEqualTo("Transactions are proxies.");
        assertThat(followUpRequest.thread().get(0).skipped()).isFalse();
        assertThat(followUpRequest.memorySummary()).contains("Ran Kafka in production").contains("Knows proxies.");
        assertThat(followUpRequest.codingLanguage()).isNull();

        EvaluationRequest codingRequest = evaluator.requests().stream()
                .filter(request -> request.answerId().equals(codingFollowUp.answerId())).findFirst().orElseThrow();
        assertThat(codingRequest.evaluator()).isEqualTo(Evaluator.CODING);
        assertThat(codingRequest.topic()).isNull();
        assertThat(codingRequest.codingLanguage()).as("a follow-up without a code language gets the interview's language").isEqualTo("java");
        assertThat(codingRequest.thread()).hasSize(1);
        assertThat(codingRequest.thread().get(0).answerText()).isEqualTo("int f() { return 1; }");
    }

    @Test
    void theRequestCarriesTheFocusAreasOfTheSkillProfileAndTheMistakesTheInterviewRecorded() {
        ScenarioBuilder builder = newInterview();
        Asked react = builder.technical("react/state-management", 3, "Lift the state up to the common parent.");
        Asked coding = builder.coding(3, "int f() { return 1; }");
        Asked design = builder.design(4, "A queue and workers.");
        ScenarioBuilder.Built built = builder.buildFinished();
        inTransaction(() -> {
            Interview interview = interviews.findById(built.interviewId()).orElseThrow();
            interview.replaceMemory(new InterviewMemory(Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), null, null,
                    List.of(new Misconception("useEffect runs before render", "react/hooks"), new Misconception("keys only matter for speed", null))),
                    ScenarioBuilder.START);
            interviews.saveAndFlush(interview);
        });

        service.evaluatePending(built.interviewId());

        EvaluationRequest reactRequest = requestFor(react);
        assertThat(reactRequest.focusAreas()).as("the react profile of application.yml")
                .anyMatch(area -> area.contains("component architecture")).anyMatch(area -> area.contains("state management"));
        assertThat(reactRequest.earlierMistakes()).containsExactly("useEffect runs before render", "keys only matter for speed");
        assertThat(requestFor(coding).focusAreas()).as("Java code: the java profile").anyMatch(area -> area.contains("Java memory model"));
        assertThat(requestFor(design).focusAreas()).as("a design problem: the system-design profile")
                .anyMatch(area -> area.startsWith("scalability"));
    }

    private EvaluationRequest requestFor(Asked asked) {
        return evaluator.requests().stream().filter(request -> request.answerId().equals(asked.answerId())).findFirst().orElseThrow();
    }

    @Test
    void theThreadOfADeepFollowUpListsEveryEarlierTurnOldestFirstAndMarksSkippedTurns() {
        ScenarioBuilder builder = newInterview();
        Asked root = builder.technical("java/concurrency", 3, "The root answer.");
        Asked first = builder.followUp(root, Section.QUESTIONING, "java/concurrency", 3, null);
        Asked second = builder.followUp(first, Section.QUESTIONING, "java/concurrency", 3, "The deepest answer.");
        ScenarioBuilder.Built built = builder.buildFinished();
        jdbc.update("update answers set evaluation_status = 'COMPLETED' where id in (?, ?)", root.answerId(), first.answerId());

        service.evaluatePending(built.interviewId());

        EvaluationRequest request = evaluator.requests().stream()
                .filter(candidate -> candidate.answerId().equals(second.answerId())).findFirst().orElseThrow();
        assertThat(request.thread()).hasSize(2);
        assertThat(request.thread().get(0).questionPrompt()).startsWith("Question number 1");
        assertThat(request.thread().get(0).answerText()).isEqualTo("The root answer.");
        assertThat(request.thread().get(0).skipped()).isFalse();
        assertThat(request.thread().get(1).questionPrompt()).startsWith("Question number 2");
        assertThat(request.thread().get(1).skipped()).isTrue();
    }

    @Test
    void aStoredEvaluationCarriesTheModelCallOfTheAssessment() {
        ScenarioBuilder builder = newInterview();
        Asked technical = builder.technical("java/concurrency", 3, "an answer");
        ScenarioBuilder.Built built = builder.buildFinished();
        evaluator.script(technical.answerId(), request -> {
            AnswerAssessment base = Assessments.uniform(request.evaluator(), 3, request.expectedKeyPoints());
            return new AnswerAssessment(base.ratings(), base.keyPoints(), base.factualErrors(), base.candidateClaims(), base.summary(),
                    base.flags(), base.promptId(), base.promptVersion(), base.model(), Fixtures.EVALUATION_LLM_CALL);
        });

        service.evaluatePending(built.interviewId());

        assertThat(jdbc.queryForObject("select llm_call_id from evaluations where answer_id = ?", UUID.class, technical.answerId()))
                .isEqualTo(Fixtures.EVALUATION_LLM_CALL);
    }

    @Test
    void aCodeAnswerKeepsItsOwnLanguageInTheRequest() {
        ScenarioBuilder builder = newInterview();
        Asked coding = builder.coding(3, "print(1)");
        ScenarioBuilder.Built built = builder.buildFinished();
        jdbc.update("update answers set code_language = 'python' where id = ?", coding.answerId());

        service.evaluatePending(built.interviewId());

        assertThat(evaluator.requests()).singleElement().extracting(EvaluationRequest::codingLanguage).isEqualTo("python");
    }

    @Test
    void whenTheEvaluatorGoesDownMidWayTheRestIsRetriedThreeTimesAndGivenUp() {
        Three three = threePending();
        evaluator.unavailableAfter(1);

        PendingEvaluationResult result = service.evaluatePending(three.built().interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(1, 0, 2));
        assertThat(result.complete()).as("nothing waits any more: the interview can be finished").isTrue();
        assertThat(evaluator.calls()).as("one success, then three attempts for each of the two others").isEqualTo(7);
        assertThat(pause.pauses()).as("one shared wait per round, not one per answer")
                .containsExactly(java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(4));
        assertThat(statusOf(three.first())).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(statusOf(three.second())).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(statusOf(three.third())).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(evaluationRows(three.built().interviewId())).isEqualTo(1);

        evaluator.reset();
        PendingEvaluationResult later = service.evaluatePending(three.built().interviewId());

        assertThat(later).as("a given-up answer is final").isEqualTo(new PendingEvaluationResult(0, 0, 2));
        assertThat(evaluator.calls()).isZero();
    }

    @Test
    void anEvaluatorThatIsDownFromTheStartMakesEveryAnswerUnassessableAfterThreeAttemptsAndScoresNothing() {
        Three three = threePending();
        evaluator.unavailable();

        PendingEvaluationResult result = service.evaluatePending(three.built().interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(0, 0, 3));
        assertThat(evaluator.calls()).isEqualTo(9);
        assertThat(evaluationRows(three.built().interviewId())).isZero();
        for (Asked asked : List.of(three.first(), three.second(), three.third())) {
            assertThat(statusOf(asked)).isEqualTo(EvaluationStatus.UNASSESSABLE);
            assertThat(attemptsOf(asked)).isEqualTo(3);
            assertThat(failureOf(asked)).isEqualTo("PROVIDER_FAILURE");
            assertThat(jdbc.queryForObject("select next_evaluation_at from answers where id = ?", java.sql.Timestamp.class, asked.answerId())).isNull();
        }
        assertThat(pause.pauses()).as("the waiting does not grow with the number of answers")
                .containsExactly(java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(4));
    }

    @Test
    void anAssessmentThatIsRejectedEveryTimeEndsAsUnassessableAndTheRunContinues() {
        Three three = threePending();
        AnswerAssessment wrongRubric = Assessments.builder(Evaluator.CODING).build();
        evaluator.script(three.second().answerId(), request -> wrongRubric);

        PendingEvaluationResult result = service.evaluatePending(three.built().interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(2, 0, 1));
        assertThat(evaluator.calls()).isEqualTo(5);
        assertThat(statusOf(three.first())).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(statusOf(three.second())).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(statusOf(three.third())).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(count("evaluations", "answer_id", three.second().answerId())).isZero();
        assertThat(failureOf(three.second())).isEqualTo("INVALID_OUTPUT");
    }

    private String failureOf(Asked asked) {
        return jdbc.queryForObject("select evaluation_failure from answers where id = ?", String.class, asked.answerId());
    }

    private int attemptsOf(Asked asked) {
        return jdbc.queryForObject("select evaluation_attempts from answers where id = ?", Integer.class, asked.answerId());
    }

    @Test
    void anAnswerThatFailedAnAttemptEarlierIsEvaluatedAgainOnceItsBackoffHasPassed() {
        Three three = threePending();
        jdbc.update("update answers set evaluation_attempts = 1, evaluation_failure = 'PROVIDER_FAILURE', next_evaluation_at = ? where id = ?",
                java.sql.Timestamp.from(clock.instant().plusSeconds(2)), three.second().answerId());

        PendingEvaluationResult result = service.evaluatePending(three.built().interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(3, 0));
        assertThat(pause.pauses()).as("it waited for the backoff of the earlier attempt").containsExactly(java.time.Duration.ofSeconds(2));
        assertThat(statusOf(three.second())).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(attemptsOf(three.second())).isEqualTo(2);
        assertThat(failureOf(three.second())).as("a success clears the failure").isNull();
    }

    @Test
    void anInterviewWithNothingPendingCallsNoPort() {
        Three three = threePending();
        service.evaluatePending(three.built().interviewId());
        evaluator.reset();

        PendingEvaluationResult again = service.evaluatePending(three.built().interviewId());

        assertThat(again).isEqualTo(new PendingEvaluationResult(0, 0));
        assertThat(evaluator.calls()).isZero();
    }

    @Test
    void evaluatingOneInterviewNeverTouchesTheAnswersOfAnother() {
        Three mine = threePending();
        Three other = threePending();

        service.evaluatePending(mine.built().interviewId());

        assertThat(evaluationRows(mine.built().interviewId())).isEqualTo(3);
        assertThat(evaluationRows(other.built().interviewId())).isZero();
        assertThat(statusOf(other.first())).isEqualTo(EvaluationStatus.PENDING);
    }

    @Test
    void twoRunsOverTheSameAnswersEvaluateEachAnswerOnceBecauseTheSecondRunCannotClaimAnAnswerThatIsBeingEvaluated() throws Exception {
        Three three = threePending();
        com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Gate gate = evaluator.blockNextCall();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PendingEvaluationResult> a = pool.submit(() -> service.evaluatePending(three.built().interviewId()));
            gate.awaitEntered();

            PendingEvaluationResult b = service.evaluatePending(three.built().interviewId());

            assertThat(b).as("the other run evaluated the answers nobody holds and left the held one alone")
                    .isEqualTo(new PendingEvaluationResult(2, 1));
            gate.release();
            assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(new PendingEvaluationResult(1, 0));
        } finally {
            gate.release();
            pool.shutdownNow();
        }

        assertThat(evaluator.calls()).as("each answer reached the evaluator once").isEqualTo(3);
        assertThat(evaluationRows(three.built().interviewId())).isEqualTo(3);
        for (Asked asked : List.of(three.first(), three.second(), three.third())) {
            assertThat(count("evaluations", "answer_id", asked.answerId())).isEqualTo(1);
            assertThat(statusOf(asked)).isEqualTo(EvaluationStatus.COMPLETED);
            assertThat(attemptsOf(asked)).isEqualTo(1);
        }
    }

    @Test
    void anEvaluatorThatThrowsSomethingElseIsCountedAsAProviderFailureNotSwallowedSilently() {
        Three three = threePending();
        evaluator.script(three.first().answerId(), request -> {
            throw new IllegalStateException("evaluator bug");
        });

        PendingEvaluationResult result = service.evaluatePending(three.built().interviewId());

        assertThat(result).isEqualTo(new PendingEvaluationResult(2, 0, 1));
        assertThat(statusOf(three.first())).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(failureOf(three.first())).isEqualTo("PROVIDER_FAILURE");
        assertThat(attemptsOf(three.first())).isEqualTo(3);
    }

    @Test
    void criteriaOfTheRequestedKindAreWhatTheDoubleRates() {
        ScenarioBuilder builder = newInterview();
        builder.design(4, "A design.");
        ScenarioBuilder.Built built = builder.buildFinished();

        service.evaluatePending(built.interviewId());

        List<String> criteria = jdbc.queryForList(
                "select jsonb_array_elements(criterion_scores)->>'criterion' from evaluations e join answers a on a.id = e.answer_id where a.interview_id = ?",
                String.class, built.interviewId());
        assertThat(criteria).containsExactlyInAnyOrderElementsOf(
                RubricRegistry.criteria(Evaluator.SYSTEM_DESIGN).stream().map(Criterion::name).toList());
    }
}
