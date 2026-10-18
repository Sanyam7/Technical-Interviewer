package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Whole interviews through the real endpoints, from the start to the finished report. */
class TurnSequenceIT extends TurnApiTestBase {

    /** The edges of ARCHITECTURE.md section 5, plus the creation edge and the two the engine adds (see its javadoc). */
    private static final Set<String> LEGAL_EDGES = Set.of(
            "-:INIT:CREATE",
            "INIT:INTRODUCTION:START", "INIT:ABANDONED:ABANDON",
            "INTRODUCTION:QUESTIONING:INTRO_ANSWERED", "INTRODUCTION:CODING:INTRO_ANSWERED", "INTRODUCTION:SYSTEM_DESIGN:INTRO_ANSWERED",
            "QUESTIONING:FOLLOW_UP:ANSWER_EVALUATED", "QUESTIONING:QUESTIONING:ANSWER_EVALUATED",
            "QUESTIONING:CODING:SECTION_COMPLETE", "QUESTIONING:SYSTEM_DESIGN:SECTION_COMPLETE", "QUESTIONING:FINAL_EVALUATION:SECTION_COMPLETE",
            "FOLLOW_UP:FOLLOW_UP:ANSWER_EVALUATED", "FOLLOW_UP:QUESTIONING:FOLLOW_UP_DONE", "FOLLOW_UP:CODING:FOLLOW_UP_DONE",
            "FOLLOW_UP:SYSTEM_DESIGN:FOLLOW_UP_DONE",
            "CODING:FOLLOW_UP:ANSWER_EVALUATED", "CODING:CODING:ANSWER_EVALUATED", "CODING:SYSTEM_DESIGN:SECTION_COMPLETE",
            "CODING:FINAL_EVALUATION:SECTION_COMPLETE",
            "SYSTEM_DESIGN:FOLLOW_UP:ANSWER_EVALUATED", "SYSTEM_DESIGN:FINAL_EVALUATION:SECTION_COMPLETE",
            "INTRODUCTION:FINAL_EVALUATION:TIME_UP", "QUESTIONING:FINAL_EVALUATION:TIME_UP", "FOLLOW_UP:FINAL_EVALUATION:TIME_UP",
            "CODING:FINAL_EVALUATION:TIME_UP", "SYSTEM_DESIGN:FINAL_EVALUATION:TIME_UP",
            "INTRODUCTION:FINAL_EVALUATION:END_REQUESTED", "QUESTIONING:FINAL_EVALUATION:END_REQUESTED",
            "FOLLOW_UP:FINAL_EVALUATION:END_REQUESTED", "CODING:FINAL_EVALUATION:END_REQUESTED",
            "SYSTEM_DESIGN:FINAL_EVALUATION:END_REQUESTED",
            "FINAL_EVALUATION:COMPLETED:REPORT_READY", "FINAL_EVALUATION:FINAL_EVALUATION:REPORT_FAILED");

    /** Checks every transition row of the interview: a legal edge, each one leaving the state the previous one entered. */
    private List<String> assertLegalChain(UUID interview) {
        List<String> log = transitionLog(interview);
        String previousTo = null;
        for (String row : log) {
            String from = row.substring(0, row.indexOf('>'));
            String rest = row.substring(row.indexOf('>') + 1);
            String edge = from + ":" + rest.replace(':', ':');
            assertThat(LEGAL_EDGES).as("edge " + row).contains(edge);
            if (previousTo != null) {
                assertThat(from).as("a transition leaves the state the previous one entered: " + row).isEqualTo(previousTo);
            }
            previousTo = rest.substring(0, rest.indexOf(':'));
        }
        return log;
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 30, 45, 60, 90})
    void aTechnicalInterviewOfEveryDurationRunsFromTheStartToACompletedReport(int minutes) throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(minutes));

        List<Turn> turns = play(ada, id, Duration.ofSeconds(10), TurnApiTestBase::answerFor);

        // The last response says the question phase is over and offers nothing more.
        JsonNode last = turns.getLast().response();
        assertThat(last.get("state").asString()).isEqualTo("FINAL_EVALUATION");
        assertThat(last.get("interviewComplete").asBoolean()).isTrue();
        assertThat(last.get("nextQuestion").isNull()).isTrue();
        assertThat(last.get("awaitingNextQuestion").asBoolean()).isFalse();
        assertThat(last.get("accepted").asBoolean()).isTrue();

        // The log: created, started, then only legal edges, one chain, and the question phase ends by the plan.
        List<String> log = assertLegalChain(id);
        assertThat(log.subList(0, 3)).containsExactly("->INIT:CREATE", "INIT>INTRODUCTION:START", "INTRODUCTION>QUESTIONING:INTRO_ANSWERED");
        assertThat(log.getLast()).isEqualTo("QUESTIONING>FINAL_EVALUATION:SECTION_COMPLETE");
        assertThat(stateOf(id)).isEqualTo("FINAL_EVALUATION");
        assertThat(jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("PLAN_COMPLETE");

        // The questions: consecutive numbers, the static introduction first, the plan's slots used exactly (nobody ran out of time).
        List<Map<String, Object>> questionRows = questionRows(id);
        for (int i = 0; i < questionRows.size(); i++) {
            assertThat(((Number) questionRows.get(i).get("sequence_no")).intValue()).isEqualTo(i + 1);
        }
        assertThat(questionRows.getFirst()).containsEntry("kind", "INTRO").containsEntry("source", "STATIC").containsEntry("depth", 0);
        assertThat(questionRows.subList(1, questionRows.size())).allSatisfy(row -> assertThat(row.get("source")).isEqualTo("GENERATED"));
        List<Map<String, Object>> roots = questionRows.stream().filter(row -> row.get("kind").equals("TECHNICAL")).toList();
        assertThat(roots).hasSize(slotsOf(id, "QUESTIONING"));
        assertThat(questionRows).allSatisfy(row -> assertThat(((Number) row.get("depth")).intValue()).isLessThanOrEqualTo(2));

        // The topics: never one more question than the plan's quota, and no topic twice in a row.
        JsonNode quotas = planOf(id).get("sections").get(1).get("topicQuotas");
        Map<String, Integer> asked = new HashMap<>();
        roots.forEach(row -> asked.merge((String) row.get("topic"), 1, Integer::sum));
        asked.forEach((topic, count) -> assertThat(count).as("questions on " + topic).isLessThanOrEqualTo(quotas.get(topic).asInt()));
        for (int i = 1; i < roots.size(); i++) {
            assertThat(roots.get(i).get("topic")).as("root question %d repeats the topic of the one before", i).isNotEqualTo(roots.get(i - 1).get("topic"));
        }

        // Every answered question has its answer and its evaluation, nothing is left pending.
        long answered = count("answers", id);
        assertThat(answered).isEqualTo(questionRows.size());
        assertThat(count("evaluations", id)).isEqualTo(answered);
        assertThat(jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND evaluation_status <> 'COMPLETED'")
                .param("id", id).query(Long.class).single()).isZero();

        // Finishing completes it with a ready report.
        JsonNode finished = body(finish(ada, id, "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));
        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(finished.get("reportStatus").asString()).isEqualTo("READY");
        assertThat(finished.get("retryable").asBoolean()).isFalse();
        assertThat(finished.get("reportUrl").asString()).isEqualTo("/api/v1/interviews/" + id + "/report");
        assertThat(transitionLog(id).getLast()).isEqualTo("FINAL_EVALUATION>COMPLETED:REPORT_READY");
        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("status").asString()).isEqualTo("READY");
        assertThat(report.get("coverage").get("questionsAnswered").asInt()).isEqualTo(questionRows.size());
        assertThat(report.get("coverage").get("skipped").asInt()).isZero();
    }

    @Test
    void anInterviewThatAsksNoFollowUpsHasTheExactSequenceOfStatesOfItsPlan() throws Exception {
        evaluator.quality(Quality.INCORRECT);
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(30));
        int slots = slotsOf(id, "QUESTIONING");

        play(ada, id, Duration.ofSeconds(10), TurnApiTestBase::answerFor);

        List<String> expected = new ArrayList<>(List.of("->INIT:CREATE", "INIT>INTRODUCTION:START", "INTRODUCTION>QUESTIONING:INTRO_ANSWERED"));
        for (int i = 1; i < slots; i++) {
            expected.add("QUESTIONING>QUESTIONING:ANSWER_EVALUATED");
        }
        expected.add("QUESTIONING>FINAL_EVALUATION:SECTION_COMPLETE");
        assertThat(transitionLog(id)).containsExactlyElementsOf(expected);
        assertThat(questionRows(id)).hasSize(1 + slots).allSatisfy(row -> assertThat(row.get("kind")).isIn("INTRO", "TECHNICAL"));
        JsonNode finished = body(finish(ada, id, "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));
        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(transitionLog(id).getLast()).isEqualTo("FINAL_EVALUATION>COMPLETED:REPORT_READY");
    }

    @Test
    void aFollowUpThreadThatUsesUpTheLastSlotRecordsBothTransitionsInOneTurn() throws Exception {
        UUID bob = newUser();
        UUID id = startedInterview(bob, technical(15));
        int slots = slotsOf(id, "QUESTIONING");
        // Poor answers end every thread at its root; the last root gets a partial answer, so a follow-up is asked.
        evaluator.quality(Quality.INCORRECT);
        clock.advance(Duration.ofSeconds(10));
        answerOk(bob, id, currentQuestionId(bob, id), "intro");
        for (int i = 1; i <= slots; i++) {
            clock.advance(Duration.ofSeconds(10));
            if (i == slots) {
                evaluator.quality(Quality.PARTIAL);
            }
            answerOk(bob, id, currentQuestionId(bob, id), "answer " + i);
        }
        assertThat(stateOf(id)).isEqualTo("FOLLOW_UP");
        // The follow-up is answered poorly: the thread ends, and with it the section, in a single turn.
        evaluator.quality(Quality.INCORRECT);
        clock.advance(Duration.ofSeconds(10));

        JsonNode closing = answerOk(bob, id, currentQuestionId(bob, id), "the follow-up answer");

        assertThat(closing.get("state").asString()).isEqualTo("FINAL_EVALUATION");
        assertThat(closing.get("interviewComplete").asBoolean()).isTrue();
        List<String> log = transitionLog(id);
        assertThat(log.subList(log.size() - 3, log.size()))
                .containsExactly("QUESTIONING>FOLLOW_UP:ANSWER_EVALUATED", "FOLLOW_UP>QUESTIONING:FOLLOW_UP_DONE", "QUESTIONING>FINAL_EVALUATION:SECTION_COMPLETE");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_state_transitions WHERE interview_id = :id AND occurred_at = ("
                + "SELECT max(occurred_at) FROM interview_state_transitions WHERE interview_id = :id)").param("id", id)
                .query(Long.class).single()).as("both rows of the closing turn carry the time of the turn").isEqualTo(2);
        assertLegalChain(id);
    }

    @Test
    void aFullLoopWalksThroughEverySectionToACompletedReport() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, FULL_LOOP_CONFIGURATION);
        assertThat(slotsOf(id, "CODING")).isGreaterThanOrEqualTo(1);

        List<Turn> turns = play(ada, id, Duration.ofSeconds(10), TurnApiTestBase::answerFor);

        List<String> visited = new ArrayList<>();
        for (String row : assertLegalChain(id)) {
            String to = row.substring(row.indexOf('>') + 1, row.lastIndexOf(':'));
            if (visited.isEmpty() || !visited.getLast().equals(to)) {
                visited.add(to);
            }
        }
        List<String> withoutFollowUps = visited.stream().filter(state -> !state.equals("FOLLOW_UP")).distinct().toList();
        assertThat(withoutFollowUps).containsExactly("INIT", "INTRODUCTION", "QUESTIONING", "CODING", "SYSTEM_DESIGN", "FINAL_EVALUATION");
        assertThat(turns.getLast().response().get("state").asString()).isEqualTo("FINAL_EVALUATION");

        List<Map<String, Object>> rows = questionRows(id);
        assertThat(rows.stream().map(row -> (String) row.get("section")).distinct().toList())
                .containsExactly("INTRODUCTION", "QUESTIONING", "CODING", "SYSTEM_DESIGN");
        assertThat(rows.stream().filter(row -> row.get("kind").equals("CODING")).count()).isEqualTo(slotsOf(id, "CODING"));
        assertThat(rows.stream().filter(row -> row.get("kind").equals("SYSTEM_DESIGN")).count()).isEqualTo(1);
        assertThat(rows.stream().filter(row -> row.get("kind").equals("INTRO")).count()).isEqualTo(1);
        for (Map<String, Object> row : rows) {
            String rubric = (String) row.get("rubric_id");
            String section = (String) row.get("section");
            Evaluator evaluatorKind = switch (section) {
                case "INTRODUCTION" -> Evaluator.INTRO;
                case "QUESTIONING" -> Evaluator.TECHNICAL;
                case "CODING" -> Evaluator.CODING;
                default -> Evaluator.SYSTEM_DESIGN;
            };
            assertThat(rubric).as("rubric of question %s", row.get("sequence_no")).isEqualTo(RubricRegistry.forEvaluator(evaluatorKind).id());
        }

        // The generator was told the language for every coding request and no topic for coding and design.
        generator.requests().forEach(request -> {
            switch (request.section()) {
                case CODING -> {
                    assertThat(request.codingLanguage()).isEqualTo("java");
                    assertThat(request.topic()).isNull();
                }
                case SYSTEM_DESIGN -> {
                    assertThat(request.codingLanguage()).isNull();
                    assertThat(request.topic()).isNull();
                }
                default -> assertThat(request.topic()).isNotNull();
            }
        });

        JsonNode finished = body(finish(ada, id, "ACKNOWLEDGE_COMPLETE").andExpect(status().isOk()));
        assertThat(finished.get("state").asString()).isEqualTo("COMPLETED");
        assertThat(finished.get("reportStatus").asString()).isEqualTo("READY");
        JsonNode report = body(getAs(ada, "/interviews/" + id + "/report").andExpect(status().isOk()));
        assertThat(report.get("sections").findValuesAsString("section")).contains("QUESTIONING", "CODING", "SYSTEM_DESIGN");
    }

    @Test
    void aCandidateWhoTakesLongOnEveryQuestionGetsFewerQuestionsThanTheSlotsBecauseTheSectionBudgetRunsOut() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(45));
        int slots = slotsOf(id, "QUESTIONING");
        int minutes = 0;
        for (JsonNode planned : planOf(id).get("sections")) {
            if (planned.get("section").asString().equals("QUESTIONING")) {
                minutes = planned.get("minutes").asInt();
            }
        }

        play(ada, id, Duration.ofMinutes(5), TurnApiTestBase::answerFor);

        long roots = questionRows(id).stream().filter(row -> row.get("kind").equals("TECHNICAL")).count();
        assertThat(roots).isLessThan(slots);
        // The section stopped asking new questions once its budget (counted from its first question) was gone.
        assertThat(roots).isGreaterThanOrEqualTo(1);
        assertThat(minutes).isGreaterThan(0);
        assertThat(stateOf(id)).isEqualTo("FINAL_EVALUATION");
        assertThat(jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("PLAN_COMPLETE");
    }

    @Test
    void theQuestionsTheCandidateSeesCarryNothingTheServerKeepsToItself() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, FULL_LOOP_CONFIGURATION);
        Set<String> properties = new LinkedHashSet<>();
        List<Turn> turns = play(ada, id, Duration.ofSeconds(10), TurnApiTestBase::answerFor);
        for (Turn turn : turns) {
            turn.question().propertyNames().forEach(properties::add);
        }

        assertThat(properties).isSubsetOf("id", "sequenceNo", "kind", "isFollowUp", "section", "topic", "prompt", "answerFormat",
                "suggestedMinutes", "details", "answered", "design", "resume", "questionType");
        assertThat(turns.stream().map(turn -> turn.question().get("answerFormat").asString()).distinct()).contains("TEXT", "CODE");
    }
}
