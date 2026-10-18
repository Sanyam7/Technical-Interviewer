package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** What a request may say: only the answer. Everything else is refused before anything is stored. */
class TurnValidationIT extends TurnApiTestBase {

    private record Open(UUID user, UUID interview, UUID question) {
    }

    private Open open() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        return new Open(ada, id, currentQuestionId(ada, id));
    }

    private ObjectNode valid(Open open) {
        ObjectNode node = json.createObjectNode();
        node.put("questionId", open.question().toString());
        node.put("content", "my answer");
        return node;
    }

    private ResultActions submit(Open open, String body) throws Exception {
        return postAs(open.user(), "/interviews/" + open.interview() + "/answers", body);
    }

    private void assertNothingHappened(Open open) {
        assertThat(count("answers", open.interview())).isZero();
        assertThat(evaluator.calls()).isZero();
        assertThat(generator.calls()).isZero();
        assertThat(transitionLog(open.interview())).hasSize(2);
        assertThat(jdbc.sql("SELECT current_question_id FROM interviews WHERE id = :id").param("id", open.interview()).query(UUID.class).single())
                .isEqualTo(open.question());
    }

    // ---- unknown properties ----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"score", "state", "sequenceNo", "evaluation", "difficulty", "overallScore", "accepted", "nextQuestion",
            "interviewComplete", "version", "userId", "interviewId", "expectedKeyPoints", "feedback", "currentQuestionId"})
    void aClientCannotSendAnythingButTheAnswer(String property) throws Exception {
        Open open = open();
        ObjectNode body = valid(open);
        body.put(property, "100");

        submit(open, body.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));

        assertNothingHappened(open);
    }

    @Test
    void aSkipAndAFinishRefuseUnknownPropertiesToo() throws Exception {
        Open open = open();

        postAs(open.user(), "/interviews/" + open.interview() + "/questions/" + open.question() + "/skip", "{\"score\":100}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        finish(open.user(), open.interview(), "CANDIDATE_ENDED").andExpect(status().isOk());
        postAs(open.user(), "/interviews/" + open.interview() + "/finish", "{\"reason\":\"CANDIDATE_ENDED\",\"state\":\"COMPLETED\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        assertThat(count("answers", open.interview())).isZero();
    }

    // ---- the shape of the request --------------------------------------------------------------------------------

    @Test
    void aMalformedOrIncompleteBodyIsABadRequestAndStoresNothing() throws Exception {
        Open open = open();

        submit(open, "{not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        submit(open, "{\"questionId\":\"not-a-uuid\",\"content\":\"x\"}").andExpect(status().isBadRequest());
        submit(open, "{\"content\":\"x\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        submit(open, "{\"questionId\":\"" + open.question() + "\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        submit(open, "{\"questionId\":\"" + open.question() + "\",\"content\":\"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("content"));
        submit(open, "[]").andExpect(status().isBadRequest());
        mockMvc.perform(post(API + "/interviews/" + open.interview() + "/answers").with(as(open.user()))).andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post(API + "/interviews/" + open.interview() + "/answers").with(as(open.user())).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
        postAs(open.user(), "/interviews/not-a-uuid/answers", json.writeValueAsString(valid(open))).andExpect(status().isBadRequest());

        assertNothingHappened(open);
    }

    @Test
    void theTimeTheClientMeasuredMustBeASensibleNumber() throws Exception {
        Open open = open();
        ObjectNode negative = valid(open).put("timeTakenSeconds", -1);
        ObjectNode absurd = valid(open).put("timeTakenSeconds", 86_401);

        submit(open, negative.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("timeTakenSeconds"));
        submit(open, absurd.toString()).andExpect(status().isBadRequest());
        assertNothingHappened(open);

        submit(open, valid(open).put("timeTakenSeconds", 185).toString()).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT time_taken_seconds FROM answers WHERE question_id = :q").param("q", open.question()).query(Integer.class).single())
                .isEqualTo(185);
    }

    // ---- the limits of an answer ----------------------------------------------------------------------------------

    @Test
    void aTextAnswerMayHaveEightThousandCharactersAndNotOneMore() throws Exception {
        Open open = open();
        ObjectNode tooLong = valid(open).put("content", "x".repeat(8001));

        submit(open, tooLong.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        assertNothingHappened(open);

        submit(open, valid(open).put("content", "x".repeat(8000)).toString()).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT char_length(content) FROM answers WHERE question_id = :q").param("q", open.question()).query(Integer.class).single())
                .isEqualTo(8000);
    }

    @Test
    void aTextAnswerMustNotNameACodeLanguage() throws Exception {
        Open open = open();

        submit(open, valid(open).put("codeLanguage", "java").toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("codeLanguage"));

        assertNothingHappened(open);
    }

    /** Plays a FULL_LOOP interview with text answers until its first coding question is open. */
    private Open openCodingQuestion() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, FULL_LOOP_CONFIGURATION);
        JsonNode current = currentQuestion(ada, id);
        while (!current.get("answerFormat").asString().equals("CODE")) {
            clock.advance(Duration.ofSeconds(10));
            current = answerOk(ada, id, UUID.fromString(current.get("id").asString()), "text").get("nextQuestion");
        }
        return new Open(ada, id, UUID.fromString(current.get("id").asString()));
    }

    @Test
    void aCodeAnswerNeedsTheLanguageOfTheInterviewAndMayHaveTwentyThousandCharacters() throws Exception {
        Open open = openCodingQuestion();
        long answersBefore = count("answers", open.interview());

        submit(open, valid(open).toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("codeLanguage"));
        submit(open, valid(open).put("codeLanguage", "python").toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("codeLanguage"));
        submit(open, valid(open).put("codeLanguage", "Java").toString()).andExpect(status().isBadRequest());
        submit(open, valid(open).put("codeLanguage", " ").toString()).andExpect(status().isBadRequest());
        submit(open, valid(open).put("codeLanguage", "java").put("content", "x".repeat(20_001)).toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        assertThat(count("answers", open.interview())).isEqualTo(answersBefore);

        submit(open, valid(open).put("codeLanguage", "java").put("content", "x".repeat(20_000)).toString()).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT code_language FROM answers WHERE question_id = :q").param("q", open.question()).query(String.class).single())
                .isEqualTo("java");
        assertThat(count("answers", open.interview())).isEqualTo(answersBefore + 1);
    }

    @Test
    void everyViolationOfOneAnswerIsReportedTogether() throws Exception {
        Open open = open();

        ResultActions result = submit(open, valid(open).put("codeLanguage", "go").put("content", "x".repeat(8_001)).toString());

        result.andExpect(status().isBadRequest());
        List<String> fields = body(result).get("errors").findValuesAsString("field");
        assertThat(fields).containsExactlyInAnyOrder("content", "codeLanguage");
    }

    @Test
    void theBodyOfASkipIsOptionalAndCarriesOnlyTheTime() throws Exception {
        Open open = open();

        postAs(open.user(), "/interviews/" + open.interview() + "/questions/" + open.question() + "/skip", "{\"timeTakenSeconds\":-5}")
                .andExpect(status().isBadRequest());
        assertNothingHappened(open);
        postAs(open.user(), "/interviews/" + open.interview() + "/questions/" + open.question() + "/skip", "{\"timeTakenSeconds\":7}")
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT time_taken_seconds FROM answers WHERE question_id = :q").param("q", open.question()).query(Integer.class).single())
                .isEqualTo(7);
    }

    @Test
    void aFinishNeedsAKnownReason() throws Exception {
        Open open = open();

        postAs(open.user(), "/interviews/" + open.interview() + "/finish", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postAs(open.user(), "/interviews/" + open.interview() + "/finish", "{\"reason\":\"BECAUSE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        mockMvc.perform(post(API + "/interviews/" + open.interview() + "/finish").with(as(open.user()))).andExpect(status().isUnsupportedMediaType());

        assertThat(stateOf(open.interview())).isEqualTo("INTRODUCTION");
    }
}
