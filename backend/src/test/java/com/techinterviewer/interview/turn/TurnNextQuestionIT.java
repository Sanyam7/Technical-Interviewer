package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.support.ScriptedQuestionGenerator;
import com.techinterviewer.interview.domain.InterviewState;
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

/** POST /questions/next: idempotent recovery of a question that could not be generated. */
class TurnNextQuestionIT extends TurnApiTestBase {

    private MvcResult next(UUID user, UUID interview) throws Exception {
        return postAs(user, "/interviews/" + interview + "/questions/next").andReturn();
    }

    /** An interview in QUESTIONING that waits for its first technical question because the generator is down. */
    private UUID awaiting(UUID user) throws Exception {
        UUID id = startedInterview(user);
        generator.unavailable();
        answerIntroduction(user, id);
        generator.available();
        assertThat(session(user, id).get("awaitingNextQuestion").asBoolean()).isTrue();
        return id;
    }

    @Test
    void whenAQuestionIsOpenTheSessionComesBackUnchangedAndNothingIsGenerated() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        JsonNode before = session(ada, id);
        int calls = generator.calls();

        JsonNode after = body(postAs(ada, "/interviews/" + id + "/questions/next").andExpect(status().isOk()));

        assertThat(after.get("currentQuestion")).isEqualTo(before.get("currentQuestion"));
        assertThat(after.get("version")).isEqualTo(before.get("version"));
        assertThat(after.get("state").asString()).isEqualTo("INTRODUCTION");
        assertThat(generator.calls()).isEqualTo(calls);
        assertThat(count("questions", id)).isEqualTo(1);
    }

    @Test
    void anInterviewThatAsksNoQuestionsAnswersConflictWithItsState() throws Exception {
        UUID ada = newUser();
        UUID notStarted = createInterview(ada);
        UUID abandoned = createInterview(ada);
        postAs(ada, "/interviews/" + abandoned + "/abandon").andExpect(status().isOk());
        var ended = fixture.inState(ada, InterviewState.FINAL_EVALUATION, clock.instant());
        var completed = fixture.inState(ada, InterviewState.COMPLETED, clock.instant());

        for (var target : List.of(
                java.util.Map.entry(notStarted, "INIT"), java.util.Map.entry(abandoned, "ABANDONED"),
                java.util.Map.entry(ended.getId(), "FINAL_EVALUATION"), java.util.Map.entry(completed.getId(), "COMPLETED"))) {
            postAs(ada, "/interviews/" + target.getKey() + "/questions/next").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION")).andExpect(jsonPath("$.currentState").value(target.getValue()));
        }
        assertThat(generator.calls()).isZero();
    }

    @Test
    void anInterviewWithNothingDecidedAndNothingOpenIsLeftAlone() throws Exception {
        UUID ada = newUser();
        var interview = fixture.inState(ada, InterviewState.QUESTIONING, clock.instant());

        JsonNode session = body(postAs(ada, "/interviews/" + interview.getId() + "/questions/next").andExpect(status().isOk()));

        assertThat(session.get("awaitingNextQuestion").asBoolean()).isFalse();
        assertThat(session.get("currentQuestion").isNull()).isTrue();
        assertThat(generator.calls()).isZero();
    }

    @Test
    void whileTheGeneratorIsStillDownEveryCallIs503AndNothingChanges() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        generator.unavailable();
        answerIntroduction(ada, id);
        long version = session(ada, id).get("version").asLong();
        String memory = jdbc.sql("SELECT memory::text FROM interviews WHERE id = :id").param("id", id).query(String.class).single();

        for (int i = 0; i < 3; i++) {
            MvcResult result = next(ada, id);
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getContentAsString()).contains("AI_PROVIDER_UNAVAILABLE");
        }

        assertThat(session(ada, id).get("version").asLong()).isEqualTo(version);
        assertThat(jdbc.sql("SELECT memory::text FROM interviews WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo(memory);
        assertThat(count("questions", id)).isEqualTo(1);
    }

    @Test
    void twoRequestsThatBothGenerateStoreExactlyOneQuestionAndBothSeeIt() throws Exception {
        UUID ada = newUser();
        UUID id = awaiting(ada);
        ScriptedQuestionGenerator.Gate gate = generator.blockNextCall();
        int calls = generator.calls();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // The first request is inside the generator when the second one arrives, generates and stores its question.
            Future<MvcResult> slow = pool.submit(() -> next(ada, id));
            gate.awaitEntered();
            JsonNode fast = body(postAs(ada, "/interviews/" + id + "/questions/next").andExpect(status().isOk()));
            gate.release();
            JsonNode slowBody = json.readTree(slow.get(60, TimeUnit.SECONDS).getResponse().getContentAsString());

            assertThat(generator.calls() - calls).isEqualTo(2);
            assertThat(count("questions", id)).as("the slower result was dropped").isEqualTo(2);
            assertThat(slowBody.get("currentQuestion").get("id")).isEqualTo(fast.get("currentQuestion").get("id"));
            assertThat(slowBody.get("awaitingNextQuestion").asBoolean()).isFalse();
        } finally {
            gate.release();
            pool.shutdownNow();
        }
    }

    @Test
    void manyRequestsAtOnceNeverCreateTwoQuestions() throws Exception {
        for (int round = 0; round < 4; round++) {
            UUID ada = newUser();
            UUID id = awaiting(ada);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<Future<MvcResult>> futures = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Callable<MvcResult> call = () -> {
                        go.await();
                        return next(ada, id);
                    };
                    futures.add(pool.submit(call));
                }
                go.countDown();
                List<String> questionIds = new ArrayList<>();
                for (Future<MvcResult> future : futures) {
                    MvcResult result = future.get(60, TimeUnit.SECONDS);
                    assertThat(result.getResponse().getStatus()).as("round %d: %s", round, result.getResponse().getContentAsString()).isEqualTo(200);
                    questionIds.add(json.readTree(result.getResponse().getContentAsString()).get("currentQuestion").get("id").asString());
                }
                assertThat(questionIds).hasSize(4).containsOnly(questionIds.getFirst());
            } finally {
                pool.shutdownNow();
            }
            assertThat(count("questions", id)).as("round %d", round).isEqualTo(2);
            assertThat(jdbc.sql("SELECT memory -> 'pendingStep' IS NULL FROM interviews WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
        }
    }

    @Test
    void theQuestionStoredByTheRecoveryIsRecordedLikeAnyOther() throws Exception {
        UUID ada = newUser();
        UUID id = awaiting(ada);

        JsonNode session = body(postAs(ada, "/interviews/" + id + "/questions/next").andExpect(status().isOk()));

        var row = questionRows(id).get(1);
        assertThat(row).containsEntry("kind", "TECHNICAL").containsEntry("source", "GENERATED").containsEntry("depth", 0);
        assertThat(((Number) row.get("sequence_no")).intValue()).isEqualTo(2);
        assertThat(session.get("currentQuestion").get("id").asString()).isEqualTo(row.get("id").toString());
        assertThat(jdbc.sql("SELECT current_question_id FROM interviews WHERE id = :id").param("id", id).query(UUID.class).single())
                .isEqualTo(row.get("id"));
        assertThat(jdbc.sql("SELECT memory -> 'sectionProgress' -> 'QUESTIONING' IS NOT NULL FROM interviews WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).as("the section was started when its first question was asked").isTrue();
    }
}
