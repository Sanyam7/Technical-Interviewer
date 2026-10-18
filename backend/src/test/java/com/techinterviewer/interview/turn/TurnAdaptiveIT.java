package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.port.QuestionRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The adaptive behaviour of the interview as the question generator sees it: every decision the engine makes after an
 * answer (follow up, probe a gap or a claim, go deeper, move on, ask easier questions) shows in the request the
 * generator receives for the next question.
 */
class TurnAdaptiveIT extends TurnApiTestBase {

    private QuestionRequest request(int index) {
        return generator.requests().get(index);
    }

    @Test
    void aPartialAnswerIsFollowedUpOnTheFirstMissingKeyPointOfTheQuestion() throws Exception {
        evaluator.quality(Quality.PARTIAL);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        JsonNode root = currentQuestion(ada, id);

        JsonNode response = answerCurrent(ada, id, "my partial answer");

        assertThat(response.get("state").asString()).isEqualTo("FOLLOW_UP");
        QuestionRequest asked = request(1);
        assertThat(asked.kind()).isEqualTo(QuestionKind.FOLLOW_UP);
        assertThat(asked.section()).isEqualTo(Section.QUESTIONING);
        assertThat(asked.followUp().mode()).isEqualTo(FollowUpMode.PROBE_GAP);
        assertThat(asked.followUp().targetKeyPoint().id()).as("kp1 was covered, kp2 is the first one missing").isEqualTo("kp2");
        assertThat(asked.followUp().targetClaim()).isNull();
        assertThat(asked.followUp().parentQuestionPrompt()).isEqualTo(root.get("prompt").asString());
        assertThat(asked.followUp().candidateAnswer()).isEqualTo("my partial answer");
        assertThat(asked.topic()).isEqualTo(request(0).topic());
        assertThat(asked.targetDifficulty()).isEqualTo(request(0).targetDifficulty());
        assertThat(asked.askedQuestions()).hasSize(2);
        assertThat(asked.askedQuestions().get(1).prompt()).isEqualTo(root.get("prompt").asString());
        JsonNode followUp = response.get("nextQuestion");
        assertThat(followUp.get("kind").asString()).isEqualTo("FOLLOW_UP");
        assertThat(followUp.get("isFollowUp").asBoolean()).isTrue();
        Map<String, Object> row = questionRows(id).get(2);
        assertThat(row).containsEntry("depth", 1).containsEntry("parent_question_id", UUID.fromString(root.get("id").asString()));
    }

    @Test
    void aFollowUpIsFollowedUpOnceMoreAndThenTheInterviewMovesOnBecauseTheDepthIsTwo() throws Exception {
        evaluator.quality(Quality.PARTIAL);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        answerCurrent(ada, id, "root answer");
        answerCurrent(ada, id, "first follow-up answer");
        assertThat(stateOf(id)).isEqualTo("FOLLOW_UP");

        JsonNode afterSecond = answerCurrent(ada, id, "second follow-up answer");

        assertThat(afterSecond.get("state").asString()).isEqualTo("QUESTIONING");
        assertThat(afterSecond.get("nextQuestion").get("kind").asString()).isEqualTo("TECHNICAL");
        assertThat(questionRows(id)).extracting(row -> row.get("depth")).containsExactly(0, 0, 1, 2, 0);
        assertThat(request(2).followUp().parentQuestionPrompt()).startsWith("Scripted FOLLOW_UP");
        assertThat(request(3).kind()).isEqualTo(QuestionKind.TECHNICAL);
        assertThat(request(3).topic()).isNotEqualTo(request(0).topic());
    }

    @Test
    void anExcellentAnswerGoesDeeperOnTheSameTopicOneLevelHarder() throws Exception {
        evaluator.quality(Quality.EXCELLENT);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);

        JsonNode response = answerCurrent(ada, id, "a perfect answer");

        assertThat(response.get("state").asString()).isEqualTo("FOLLOW_UP");
        QuestionRequest deeper = request(1);
        assertThat(deeper.followUp().mode()).isEqualTo(FollowUpMode.DEEPEN);
        assertThat(deeper.followUp().targetKeyPoint()).isNull();
        assertThat(deeper.targetDifficulty()).isEqualTo(request(0).targetDifficulty() + 1);
        assertThat(deeper.topic()).isEqualTo(request(0).topic());
    }

    @Test
    void aClaimMadeInAFundamentalsAnswerIsNotChasedTheAnswerIsDeepenedOnItsOwnSubject() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        evaluator.quality(Quality.EXCELLENT);
        answerIntroduction(ada, id);
        evaluator.claimNext(Quality.EXCELLENT, List.of("I migrated a monolith to Kafka at my last job"));

        JsonNode response = answerCurrent(ada, id, "an excellent answer that mentions the migration");

        assertThat(request(0).questionType().stage()).isEqualTo(InterviewStage.FUNDAMENTALS);
        assertThat(response.get("state").asString()).isEqualTo("FOLLOW_UP");
        assertThat(request(1).followUp().mode()).isEqualTo(FollowUpMode.DEEPEN);
        assertThat(request(0).candidateClaims()).as("a fundamentals question does not see what the candidate said").isEmpty();
    }

    @Test
    void aClaimTheCandidateMakesAboutTheirOwnWorkIsProbedOnceInsteadOfGoingDeeper() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, with(with(technical(45), "strategy", "CUSTOM"), "stageMix",
                java.util.Map.of("fundamentals", 0, "practical", 0, "project", 100)));
        evaluator.quality(Quality.EXCELLENT);
        evaluator.claimNext(Quality.EXCELLENT, List.of("I built the payment service of my last employer"));
        answerIntroduction(ada, id);
        assertThat(request(0).questionType()).isEqualTo(QuestionType.EXPERIENCE);
        assertThat(request(0).candidateClaims()).containsExactly("I built the payment service of my last employer");
        evaluator.claimNext(Quality.EXCELLENT, List.of("I migrated a monolith to Kafka at my last job"));

        JsonNode response = answerCurrent(ada, id, "an excellent answer that mentions the migration");

        assertThat(response.get("state").asString()).isEqualTo("FOLLOW_UP");
        QuestionRequest probe = request(1);
        assertThat(probe.followUp().mode()).isEqualTo(FollowUpMode.PROBE_CLAIM);
        assertThat(probe.followUp().targetClaim()).isEqualTo("I migrated a monolith to Kafka at my last job");
        assertThat(probe.followUp().targetKeyPoint()).isNull();
        // The claim is probed once: the answer to the probe (excellent again, no new claim) goes deeper instead.
        answerCurrent(ada, id, "a good answer about Kafka");
        assertThat(request(2).followUp().mode()).isEqualTo(FollowUpMode.DEEPEN);
    }

    @Test
    void aPoorAnswerMovesOnToAnotherTopicWithoutAFollowUp() throws Exception {
        evaluator.quality(Quality.INCORRECT);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);

        JsonNode response = answerCurrent(ada, id, "no idea");

        assertThat(response.get("state").asString()).isEqualTo("QUESTIONING");
        assertThat(response.get("nextQuestion").get("kind").asString()).isEqualTo("TECHNICAL");
        assertThat(request(1).kind()).isEqualTo(QuestionKind.TECHNICAL);
        assertThat(request(1).followUp()).isNull();
        assertThat(request(1).topic()).isNotEqualTo(request(0).topic());
        assertThat(response.get("progress").get("questionsAnswered").asInt()).isEqualTo(1);
    }

    /** The levels at which each topic of a long one-skill interview was asked, in order, when every answer was of this quality. */
    private List<List<Integer>> levelsOfRepeatedTopics(Quality quality) throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, with(with(technical(90), "skills", List.of("java")), "difficulty", "MEDIUM"));
        evaluator.quality(quality);
        answerIntroduction(ada, id);
        while (stateOf(id).equals("QUESTIONING") || stateOf(id).equals("FOLLOW_UP")) {
            answerCurrent(ada, id, "an answer");
        }
        Map<String, List<Integer>> levels = new java.util.LinkedHashMap<>();
        generator.requests().stream().filter(request -> request.kind() == QuestionKind.TECHNICAL).forEach(request ->
                levels.computeIfAbsent(request.topic().key(), key -> new java.util.ArrayList<>()).add(request.targetDifficulty()));
        return levels.values().stream().filter(asked -> asked.size() > 1).toList();
    }

    @Test
    void aTopicThatWentBadlyIsAskedAgainEasier() throws Exception {
        List<List<Integer>> repeated = levelsOfRepeatedTopics(Quality.INCORRECT);

        assertThat(repeated).as("a 90 minute interview about one skill asks some topic twice").isNotEmpty();
        repeated.forEach(asked -> assertThat(asked.get(1)).isLessThanOrEqualTo(asked.get(0)));
        assertThat(repeated).anySatisfy(asked -> assertThat(asked.get(1)).isLessThan(asked.get(0)));
    }

    @Test
    void aTopicThatWentWellIsAskedAgainHarder() throws Exception {
        List<List<Integer>> repeated = levelsOfRepeatedTopics(Quality.EXCELLENT);

        assertThat(repeated).isNotEmpty();
        repeated.forEach(asked -> assertThat(asked.get(1)).isGreaterThanOrEqualTo(asked.get(0)));
        assertThat(repeated).anySatisfy(asked -> assertThat(asked.get(1)).isGreaterThan(asked.get(0)));
    }

    @Test
    void theStartLevelOfTheCodingRoundFollowsHowTheTechnicalRoundWent() throws Exception {
        UUID ada = newUser();
        UUID poor = startedInterview(ada, FULL_LOOP_CONFIGURATION);
        evaluator.quality(Quality.INCORRECT);
        play(ada, poor, java.time.Duration.ofSeconds(10), TurnApiTestBase::answerFor);
        int afterPoor = codingRequestOf(poor);

        generator.reset();
        UUID bob = newUser();
        UUID strong = startedInterview(bob, FULL_LOOP_CONFIGURATION);
        evaluator.quality(Quality.EXCELLENT);
        play(bob, strong, java.time.Duration.ofSeconds(10), TurnApiTestBase::answerFor);
        int afterStrong = codingRequestOf(strong);

        // The coding problem comes from the curated bank at exactly the decided level, so the stored difficulty is the decision.
        assertThat(afterPoor).isLessThan(afterStrong);
    }

    private int codingRequestOf(UUID interview) {
        return ((Number) questionRows(interview).stream().filter(row -> row.get("kind").equals("CODING")).findFirst().orElseThrow().get("difficulty")).intValue();
    }

    @Test
    void whenTheEvaluatorIsDownTheInterviewMovesOnAtTheSameDifficultyWithoutFollowUps() throws Exception {
        evaluator.quality(Quality.EXCELLENT);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        evaluator.unavailable();

        JsonNode response = answerCurrent(ada, id, "an answer nobody can evaluate");

        assertThat(response.get("state").asString()).isEqualTo("QUESTIONING");
        assertThat(response.get("nextQuestion").get("kind").asString()).isEqualTo("TECHNICAL");
        assertThat(request(1).followUp()).isNull();
        assertThat(request(1).targetDifficulty()).isEqualTo(request(0).targetDifficulty());
        assertThat(jdbc.sql("SELECT evaluation_status FROM answers WHERE interview_id = :id ORDER BY submitted_at, id").param("id", id)
                .query(String.class).list()).containsExactly("COMPLETED", "PENDING");
    }

    @Test
    void anAnswerThatRepeatsAnEarlierOneEndsTheFollowUpThreadAndTheTurnRecordsWhy() throws Exception {
        evaluator.quality(Quality.PARTIAL);
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        answerIntroduction(ada, id);
        String pasted = "I would protect the shared counter with a lock so that only one thread updates it at a time.";
        assertThat(answerCurrent(ada, id, pasted).get("state").asString()).as("a partial answer is probed").isEqualTo("FOLLOW_UP");

        JsonNode response = answerCurrent(ada, id, pasted);

        assertThat(response.get("state").asString()).as("the same text again: no second probe, the section moves on")
                .isEqualTo("QUESTIONING");
        JsonNode details = lastTransitionDetails(id);
        assertThat(details.get("followUp").asString()).isEqualTo("MOVE_ON: the answer repeats an earlier answer: probing it would get nothing new");
        assertThat(details.get("performance").asString()).contains("repeats an earlier answer");
        assertThat(details.get("next").asString()).startsWith("QUESTIONING ");
        assertThat(jdbc.sql("SELECT (memory -> 'flags' ->> 'REPEATED_ANSWER')::int FROM interviews WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void whenTheDeadlineLeavesNoTimeForAnotherQuestionTheInterviewEndsAtThatMomentNotAtTheDeadline() throws Exception {
        evaluator.quality(Quality.AVERAGE);
        UUID ada = newUser();
        UUID id = startedInterview(ada, technical(30));
        // A ten-minute introduction: questioning starts late, so its own budget would outlast the deadline.
        clock.advance(Duration.ofMinutes(10));
        answerIntroduction(ada, id);
        Instant deadline = jdbc.sql("SELECT deadline_at FROM interviews WHERE id = :id").param("id", id).query(Instant.class).single();
        clock.advance(Duration.between(clock.instant(), deadline.minusSeconds(100)));

        answerCurrent(ada, id, "An answer given with a minute and a half left.");

        Map<String, Object> ended = jdbc.sql("""
                        SELECT occurred_at, details::text AS details FROM interview_state_transitions
                        WHERE interview_id = :id AND event = 'TIME_UP'""").param("id", id).query().singleRow();
        assertThat(((java.sql.Timestamp) ended.get("occurred_at")).toInstant()).as("now, not the deadline still 90 seconds away")
                .isEqualTo(deadline.minusSeconds(90));
        assertThat(json.readTree((String) ended.get("details")).get("next").asString())
                .isEqualTo("end: time is up, 1m30s left in the interview, a new QUESTIONING question needs 2m00s");
        assertThat(jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("TIME_UP");
    }

    private JsonNode lastTransitionDetails(UUID interview) {
        return json.readTree(jdbc.sql("SELECT details::text FROM interview_state_transitions WHERE interview_id = :id ORDER BY id DESC LIMIT 1")
                .param("id", interview).query(String.class).single());
    }
}
