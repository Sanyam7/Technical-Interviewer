package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator;
import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Answering twice, answering at the same time, and answering the wrong question (decision D7). */
class TurnIdempotencyIT extends TurnApiTestBase {

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<MvcResult> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- resubmission -------------------------------------------------------------------------------------------

    @Test
    void resubmittingAnAnsweredQuestionReturnsTheCurrentStateWithoutEvaluatingOrStoringAnything() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        JsonNode first = answerOk(ada, id, question, "my answer");
        long evaluations = count("evaluations", id);
        int evaluatorCalls = evaluator.calls();
        int generatorCalls = generator.calls();
        long version = session(ada, id).get("version").asLong();

        JsonNode again = answerOk(ada, id, question, "my answer");

        assertThat(again).isEqualTo(first);
        assertThat(again.get("accepted").asBoolean()).isTrue();
        assertThat(count("answers", id)).isEqualTo(2);
        assertThat(count("evaluations", id)).isEqualTo(evaluations);
        assertThat(evaluator.calls()).isEqualTo(evaluatorCalls);
        assertThat(generator.calls()).isEqualTo(generatorCalls);
        assertThat(count("questions", id)).isEqualTo(3);
        assertThat(session(ada, id).get("version").asLong()).as("a retry changes nothing").isEqualTo(version);
        assertThat(transitionLog(id)).hasSize(4);
    }

    @Test
    void aRetryWithDifferentContentKeepsTheFirstAnswer() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        answerOk(ada, id, question, "my first answer");

        JsonNode again = answerOk(ada, id, question, "something else entirely");

        assertThat(again.get("accepted").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT content FROM answers WHERE question_id = :q").param("q", question).query(String.class).single())
                .isEqualTo("my first answer");
    }

    @Test
    void skippingAnAnsweredQuestionAndAnsweringASkippedOneAreRetriesToo() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        UUID intro = currentQuestionId(ada, id);
        answerOk(ada, id, intro, "intro");
        UUID question = currentQuestionId(ada, id);
        JsonNode skipped = skipOk(ada, id, question);

        assertThat(skipOk(ada, id, question)).isEqualTo(skipped);
        assertThat(skipOk(ada, id, intro).get("state").asString()).isEqualTo(skipped.get("state").asString());
        assertThat(answerOk(ada, id, question, "I changed my mind").get("state").asString()).isEqualTo(skipped.get("state").asString());
        assertThat(jdbc.sql("SELECT skipped FROM answers WHERE question_id = :q").param("q", question).query(Boolean.class).single()).isTrue();
        assertThat(count("answers", id)).isEqualTo(2);
    }

    @Test
    void aRetryAfterTheLastAnswerEndedTheQuestionPhaseStillAnswersWithTheFinalState() throws Exception {
        evaluator.quality(Quality.INCORRECT);
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(15));
        UUID last = null;
        JsonNode response = null;
        while (response == null || !response.get("interviewComplete").asBoolean()) {
            last = currentQuestionId(ada, id);
            response = answerCurrent(ada, id, "answer");
        }
        assertThat(stateOf(id)).isEqualTo("FINAL_EVALUATION");

        JsonNode retry = answerOk(ada, id, last, "answer");

        assertThat(retry).isEqualTo(response);
        body(finish(ada, id, "ACKNOWLEDGE_COMPLETE"));
        assertThat(stateOf(id)).isEqualTo("COMPLETED");
        JsonNode afterCompletion = answerOk(ada, id, last, "answer");
        assertThat(afterCompletion.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(afterCompletion.get("interviewComplete").asBoolean()).isTrue();
    }

    // ---- concurrency --------------------------------------------------------------------------------------------

    @Test
    void aDuplicateThatArrivesWhileTheFirstRequestIsBeingEvaluatedAnswersAtOnceAndNothingIsDoneTwice() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        ScriptedAnswerEvaluator.Gate gate = evaluator.blockNextCall();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> original = pool.submit(() -> answer(ada, id, question, "my answer").andReturn());
            gate.awaitEntered();

            // The original has stored the answer and waits for the evaluator: the duplicate must not wait for it.
            JsonNode duplicate = bodyOf(answer(ada, id, question, "my answer").andExpect(status().isOk()).andReturn());
            assertThat(duplicate.get("accepted").asBoolean()).isTrue();
            assertThat(duplicate.get("nextQuestion").isNull()).as("the answered question is not offered again").isTrue();
            assertThat(duplicate.get("awaitingNextQuestion").asBoolean()).as("the next question is on its way").isTrue();
            assertThat(duplicate.get("interviewComplete").asBoolean()).isFalse();
            assertThat(count("answers", id)).isEqualTo(2);
            assertThat(count("evaluations", id)).as("nothing is evaluated yet").isEqualTo(1);

            gate.release();
            JsonNode completed = bodyOf(original.get(60, TimeUnit.SECONDS));
            assertThat(completed.get("nextQuestion").isNull()).isFalse();
            assertThat(count("evaluations", id)).isEqualTo(2);
            assertThat(count("questions", id)).isEqualTo(3);
            assertThat(evaluator.callsFor(jdbc.sql("SELECT id FROM answers WHERE question_id = :q").param("q", question).query(UUID.class).single()))
                    .isEqualTo(1);
            assertThat(transitionLog(id)).hasSize(4);
        } finally {
            gate.release();
            pool.shutdownNow();
        }
    }

    @Test
    void sameAnswerPostedByManyThreadsAtOnceIsEvaluatedOnceAndLeadsToOneNextQuestion() throws Exception {
        for (int round = 0; round < 4; round++) {
            UUID ada = newUser();
            UUID id = startedInterview(ada);
            answerIntroduction(ada, id);
            UUID question = currentQuestionId(ada, id);
            int evaluatorCalls = evaluator.calls();

            List<MvcResult> results = concurrently(List.of(
                    () -> answer(ada, id, question, "my answer").andReturn(),
                    () -> answer(ada, id, question, "my answer").andReturn(),
                    () -> answer(ada, id, question, "my answer").andReturn()));

            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            assertThat(count("answers", id)).isEqualTo(2);
            assertThat(count("evaluations", id)).as("round %d", round).isEqualTo(2);
            assertThat(evaluator.calls() - evaluatorCalls).as("the answer was evaluated once").isEqualTo(1);
            assertThat(count("questions", id)).as("exactly one next question").isEqualTo(3);
            assertThat(transitionLog(id)).hasSize(4);
            JsonNode current = session(ada, id).get("currentQuestion");
            assertThat(current.get("sequenceNo").asInt()).isEqualTo(3);
        }
    }

    @Test
    void twoClientsAnsweringTheSameQuestionDifferentlyLeaveExactlyOneAnswer() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID ada = newUser();
            UUID id = startedInterview(ada);
            answerIntroduction(ada, id);
            UUID question = currentQuestionId(ada, id);

            List<MvcResult> results = concurrently(List.of(
                    () -> answer(ada, id, question, "answer from the laptop").andReturn(),
                    () -> answer(ada, id, question, "answer from the phone").andReturn()));

            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            assertThat(count("answers", id)).isEqualTo(2);
            assertThat(jdbc.sql("SELECT content FROM answers WHERE question_id = :q").param("q", question).query(String.class).single())
                    .isIn("answer from the laptop", "answer from the phone");
            assertThat(count("evaluations", id)).isEqualTo(2);
            assertThat(count("questions", id)).isEqualTo(3);
        }
    }

    @Test
    void anAnswerThatRacesWithTheCandidateEndingTheInterviewIsNeverLostAndTheInterviewEnds() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        ScriptedAnswerEvaluator.Gate gate = evaluator.blockNextCall();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> answering = pool.submit(() -> answer(ada, id, question, "my answer").andReturn());
            gate.awaitEntered();
            JsonNode ended = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));
            assertThat(ended.get("reportStatus").asString()).as("the answer is being evaluated by the request that holds it").isEqualTo("FAILED");
            assertThat(ended.get("retryable").asBoolean()).isTrue();
            gate.release();

            JsonNode response = bodyOf(answering.get(60, TimeUnit.SECONDS));

            assertThat(response.get("accepted").asBoolean()).isTrue();
            assertThat(response.get("interviewComplete").asBoolean()).isTrue();
            assertThat(count("answers", id)).isEqualTo(2);
            assertThat(count("evaluations", id)).as("the interrupted answer's evaluation was kept by the request that made it").isEqualTo(2);
            JsonNode retried = body(finish(ada, id, "CANDIDATE_ENDED").andExpect(status().isOk()));
            assertThat(retried.get("state").asString()).isEqualTo("COMPLETED");
            assertThat(retried.get("reportStatus").asString()).isEqualTo("READY");
            assertThat(stateOf(id)).isEqualTo("COMPLETED");
            assertThat(evaluator.calls()).as("no answer was evaluated twice").isEqualTo(2);
        } finally {
            gate.release();
            pool.shutdownNow();
        }
    }

    // ---- a turn whose server died halfway --------------------------------------------------------------------

    @Test
    void aTurnWhoseAnswerWasStoredButNeverProcessedIsCompletedByARetryOnceItIsOldEnough() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        InterviewQuestion stored = questions.findById(question).orElseThrow();
        fixture.answer(stored, "my answer", clock.instant());
        int evaluatorCalls = evaluator.calls();

        JsonNode early = answerOk(ada, id, question, "my answer");
        assertThat(early.get("awaitingNextQuestion").asBoolean()).as("another request may still be working on it").isTrue();
        assertThat(early.get("nextQuestion").isNull()).isTrue();
        assertThat(evaluator.calls()).isEqualTo(evaluatorCalls);

        clock.advance(Duration.ofMinutes(3));
        JsonNode resumed = answerOk(ada, id, question, "my answer");

        assertThat(resumed.get("nextQuestion").isNull()).isFalse();
        assertThat(count("evaluations", id)).isEqualTo(2);
        assertThat(count("answers", id)).isEqualTo(2);
        assertThat(count("questions", id)).isEqualTo(3);
        assertThat(evaluator.calls()).isEqualTo(evaluatorCalls + 1);
        assertThat(transitionLog(id)).hasSize(4);
    }

    @Test
    void aSkipThatWasStoredButNeverProcessedIsCompletedByARetryToo() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        UUID question = currentQuestionId(ada, id);
        fixture.skip(questions.findById(question).orElseThrow(), clock.instant());
        clock.advance(Duration.ofMinutes(3));

        JsonNode resumed = skipOk(ada, id, question);

        assertThat(resumed.get("nextQuestion").isNull()).isFalse();
        assertThat(count("questions", id)).isEqualTo(3);
        assertThat(transitionLog(id)).hasSize(4);
    }

    // ---- the wrong question or the wrong state -----------------------------------------------------------------

    @Test
    void aQuestionThatIsNotTheCurrentOneIsRefusedWithTheCurrentState() throws Exception {
        UUID ada = newUser();
        InterviewQuestionPair pair = twoOpenQuestions(ada);

        answer(ada, pair.interview(), pair.older(), "an answer to the older question")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUESTION_NOT_CURRENT"))
                .andExpect(jsonPath("$.currentState").value("QUESTIONING"));
        postAs(ada, "/interviews/" + pair.interview() + "/questions/" + pair.older() + "/skip")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_NOT_CURRENT"));

        assertThat(count("answers", pair.interview())).isZero();
        assertThat(evaluator.calls()).isZero();
        answerOk(ada, pair.interview(), pair.current(), "an answer to the current question");
    }

    private record InterviewQuestionPair(UUID interview, UUID older, UUID current) {
    }

    /** An interview in QUESTIONING with two unanswered questions: the introduction is open history, the technical one is current. */
    private InterviewQuestionPair twoOpenQuestions(UUID user) {
        var interview = fixture.inState(user, InterviewState.QUESTIONING, clock.instant());
        UUID intro = questions.findByInterviewIdOrderBySequenceNoAsc(interview.getId()).getFirst().getId();
        InterviewQuestion technical = fixture.askTechnical(interview.getId(), "java", "concurrency", clock.instant());
        return new InterviewQuestionPair(interview.getId(), intro, technical.getId());
    }

    @Test
    void aQuestionOfAnotherInterviewOrAnUnknownQuestionIsNotFound() throws Exception {
        UUID ada = newUser();
        UUID one = startedInterview(ada);
        UUID other = startedInterview(ada);
        UUID foreignQuestion = currentQuestionId(ada, other);

        answer(ada, one, foreignQuestion, "an answer").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        answer(ada, one, UUID.randomUUID(), "an answer").andExpect(status().isNotFound());
        postAs(ada, "/interviews/" + one + "/questions/" + foreignQuestion + "/skip").andExpect(status().isNotFound());
        postAs(ada, "/interviews/" + one + "/questions/" + UUID.randomUUID() + "/skip").andExpect(status().isNotFound());

        assertThat(count("answers", one)).isZero();
        assertThat(count("answers", other)).isZero();
    }

    @Test
    void anInterviewThatAsksNoQuestionsRefusesAnswersWithItsState() throws Exception {
        UUID ada = newUser();
        UUID notStarted = createInterview(ada);
        UUID abandoned = createInterview(ada);
        postAs(ada, "/interviews/" + abandoned + "/abandon").andExpect(status().isOk());
        var ended = fixture.inState(ada, InterviewState.FINAL_EVALUATION, clock.instant());
        var completed = fixture.inState(ada, InterviewState.COMPLETED, clock.instant());
        UUID unansweredEnded = questions.findByInterviewIdOrderBySequenceNoAsc(ended.getId()).getFirst().getId();
        UUID unansweredCompleted = questions.findByInterviewIdOrderBySequenceNoAsc(completed.getId()).getFirst().getId();

        answer(ada, notStarted, UUID.randomUUID(), "an answer").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION")).andExpect(jsonPath("$.currentState").value("INIT"));
        answer(ada, abandoned, UUID.randomUUID(), "an answer").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION")).andExpect(jsonPath("$.currentState").value("ABANDONED"));
        answer(ada, ended.getId(), unansweredEnded, "an answer").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION")).andExpect(jsonPath("$.currentState").value("FINAL_EVALUATION"));
        answer(ada, completed.getId(), unansweredCompleted, "an answer").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION")).andExpect(jsonPath("$.currentState").value("COMPLETED"));
        postAs(ada, "/interviews/" + notStarted + "/questions/" + UUID.randomUUID() + "/skip").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        postAs(ada, "/interviews/" + completed.getId() + "/questions/" + unansweredCompleted + "/skip").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));

        assertThat(evaluator.calls()).isZero();
        assertThat(count("answers", notStarted) + count("answers", abandoned) + count("answers", ended.getId()) + count("answers", completed.getId())).isZero();
    }
}
