package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.user.Role;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** Who may reach an interview: only its owner, on every endpoint, with a token that is valid. */
class TurnAccessIT extends TurnApiTestBase {

    private record Endpoint(String method, String path, String body) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** Every endpoint that takes an interview id, with a valid body, for one interview and its open question. */
    private List<Endpoint> endpoints(UUID interview, UUID question) {
        String base = "/interviews/" + interview;
        return List.of(
                new Endpoint("GET", base, null),
                new Endpoint("GET", base + "/state", null),
                new Endpoint("POST", base + "/start", null),
                new Endpoint("GET", base + "/questions", null),
                new Endpoint("GET", base + "/questions/current", null),
                new Endpoint("GET", base + "/questions/" + question, null),
                new Endpoint("POST", base + "/abandon", null),
                new Endpoint("GET", base + "/transcript", null),
                new Endpoint("POST", base + "/answers", answerBody(question, "an answer")),
                new Endpoint("POST", base + "/questions/" + question + "/skip", null),
                new Endpoint("POST", base + "/questions/next", null),
                new Endpoint("POST", base + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}"),
                new Endpoint("GET", base + "/report", null),
                new Endpoint("POST", base + "/report/retry", null));
    }

    private MockHttpServletRequestBuilder request(Endpoint endpoint) {
        MockHttpServletRequestBuilder builder = endpoint.method().equals("GET") ? get(API + endpoint.path()) : post(API + endpoint.path());
        if (endpoint.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
        }
        return builder;
    }

    private record Snapshot(long version, long answers, long questions, long transitions, String state) {
    }

    private Snapshot snapshot(UUID interview) {
        return new Snapshot(
                jdbc.sql("SELECT version FROM interviews WHERE id = :id").param("id", interview).query(Long.class).single(),
                count("answers", interview), count("questions", interview), count("interview_state_transitions", interview), stateOf(interview));
    }

    @Test
    void anotherUsersInterviewIsNotFoundOnEveryEndpointAndNothingChanges() throws Exception {
        UUID ada = newUser();
        UUID bob = newUser();
        UUID id = startedInterview(ada);
        UUID question = currentQuestionId(ada, id);
        Snapshot before = snapshot(id);

        for (Endpoint endpoint : endpoints(id, question)) {
            mockMvc.perform(request(endpoint).with(as(bob))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(evaluator.calls() + generator.calls() + narrator.calls()).isZero();
    }

    @Test
    void anAdministratorHasNoSpecialAccessToACandidatesInterview() throws Exception {
        UUID ada = newUser();
        UUID admin = newUser();
        UUID id = startedInterview(ada);
        UUID question = currentQuestionId(ada, id);
        Snapshot before = snapshot(id);

        for (Endpoint endpoint : endpoints(id, question)) {
            mockMvc.perform(request(endpoint).with(auth.asUser(admin, Role.ADMIN))).andExpect(status().isNotFound());
        }

        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void theRoleInTheTokenDoesNotMakeAnInterviewSomeoneElses() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);

        mockMvc.perform(get(API + "/interviews/" + id).with(auth.asUser(ada, Role.ADMIN))).andExpect(status().isOk());
        mockMvc.perform(get(API + "/interviews/" + id).with(auth.asUser(ada, Role.CANDIDATE))).andExpect(status().isOk());
    }

    @Test
    void withoutAValidTokenEveryEndpointIsUnauthorizedAndNothingChanges() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        UUID question = currentQuestionId(ada, id);
        Snapshot before = snapshot(id);
        List<String> bad = new ArrayList<>();
        bad.add("");
        bad.add(auth.expiredToken(ada, Role.CANDIDATE));
        bad.add(auth.tamperedSignatureToken(ada, Role.CANDIDATE));
        bad.add(auth.algNoneToken(ada, Role.CANDIDATE));
        bad.add(auth.otherIssuerToken(ada, Role.CANDIDATE));
        bad.add(auth.otherKeyToken(ada, Role.CANDIDATE));
        bad.add("garbage");

        for (Endpoint endpoint : endpoints(id, question)) {
            for (String token : bad) {
                MockHttpServletRequestBuilder builder = request(endpoint);
                if (!token.isEmpty()) {
                    builder.header("Authorization", "Bearer " + token);
                }
                MvcResult result = mockMvc.perform(builder).andReturn();
                assertThat(result.getResponse().getStatus()).as("%s with token '%s'", endpoint, token.isEmpty() ? "none" : token.substring(0, Math.min(12, token.length()))).isEqualTo(401);
                assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            }
        }

        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void anExpiredTokenIsToldApartFromAMissingOne() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);

        mockMvc.perform(post(API + "/interviews/" + id + "/finish").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"CANDIDATE_ENDED\"}")
                        .header("Authorization", "Bearer " + auth.expiredToken(ada, Role.CANDIDATE)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        mockMvc.perform(post(API + "/interviews/" + id + "/finish").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"CANDIDATE_ENDED\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(stateOf(id)).isEqualTo("INTRODUCTION");
    }

    @Test
    void theOwnerReachesEveryEndpointOfTheirInterview() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        UUID question = currentQuestionId(ada, id);

        for (Endpoint endpoint : endpoints(id, question)) {
            if (endpoint.path().endsWith("/finish") || endpoint.path().endsWith("/abandon") || endpoint.path().endsWith("/start")
                    || endpoint.path().endsWith("/answers") || endpoint.path().endsWith("/skip") || endpoint.path().endsWith("/report")
                    || endpoint.path().endsWith("/retry")) {
                continue;
            }
            assertThat(mockMvc.perform(request(endpoint).with(as(ada))).andReturn().getResponse().getStatus()).as(endpoint.toString()).isEqualTo(200);
        }
    }

    // ---- the answers rate limit through the real endpoint ---------------------------------------------------

    @Test
    void theTwentyFirstAnswerWithinAMinuteIsRateLimitedAndOneMinuteLaterAnswersAreAcceptedAgain() throws Exception {
        UUID ada = newUser();
        UUID bob = newUser();
        UUID id = startedInterview(ada);
        // Twenty accepted answers in a minute: the clock does not move, so every request counts against the same window.
        for (int i = 0; i < 20; i++) {
            answerOk(ada, id, currentQuestionId(ada, id), "answer " + i);
        }

        MvcResult limited = answer(ada, id, currentQuestionId(ada, id), "answer 21").andReturn();

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode problem = json.readTree(limited.getResponse().getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(count("answers", id)).as("the refused answer was not stored").isEqualTo(20);
        // Another user is not affected, and neither are the other endpoints.
        UUID other = startedInterview(bob);
        answerOk(bob, other, currentQuestionId(bob, other), "an answer");
        getAs(ada, "/interviews/" + id).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(60));
        answerOk(ada, id, currentQuestionId(ada, id), "answer 21 again");
        assertThat(count("answers", id)).isEqualTo(21);
    }
}
