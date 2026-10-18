package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.user.Role;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Who may do what: every endpoint of the interview API refuses a request without a valid access token and treats the
 * configurations, interviews and questions of another user exactly like ones that do not exist.
 */
class InterviewAccessApiIT extends InterviewApiTestBase {

    /** An endpoint of the API: the URL and the body are made from the ids of the resources it addresses. */
    private record Endpoint(String name, HttpMethod method, Function<Ids, String> path, Function<Ids, String> body) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record Ids(UUID configuration, UUID interview, UUID question) {
        static Ids nonexistent() {
            return new Ids(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }
    }

    private static Endpoint endpoint(String name, HttpMethod method, Function<Ids, String> path) {
        return new Endpoint(name, method, path, null);
    }

    private static Endpoint endpoint(String name, HttpMethod method, Function<Ids, String> path, Function<Ids, String> body) {
        return new Endpoint(name, method, path, body);
    }

    /** The endpoints that address one resource by id: asking for somebody else's must look like asking for a missing one. */
    static List<Endpoint> addressedEndpoints() {
        return List.of(
                endpoint("GET configuration", HttpMethod.GET, ids -> "/interview-configurations/" + ids.configuration()),
                endpoint("DELETE configuration", HttpMethod.DELETE, ids -> "/interview-configurations/" + ids.configuration()),
                endpoint("POST interview from configuration", HttpMethod.POST, ids -> "/interviews",
                        ids -> "{\"configurationId\":\"" + ids.configuration() + "\"}"),
                endpoint("GET session", HttpMethod.GET, ids -> "/interviews/" + ids.interview()),
                endpoint("GET state", HttpMethod.GET, ids -> "/interviews/" + ids.interview() + "/state"),
                endpoint("POST start", HttpMethod.POST, ids -> "/interviews/" + ids.interview() + "/start"),
                endpoint("GET questions", HttpMethod.GET, ids -> "/interviews/" + ids.interview() + "/questions"),
                endpoint("GET current question", HttpMethod.GET, ids -> "/interviews/" + ids.interview() + "/questions/current"),
                endpoint("GET question", HttpMethod.GET, ids -> "/interviews/" + ids.interview() + "/questions/" + ids.question()),
                endpoint("POST abandon", HttpMethod.POST, ids -> "/interviews/" + ids.interview() + "/abandon"),
                endpoint("GET transcript", HttpMethod.GET, ids -> "/interviews/" + ids.interview() + "/transcript"));
    }

    /** Every endpoint of the two controllers. */
    static List<Endpoint> allEndpoints() {
        return Stream.concat(
                        addressedEndpoints().stream(),
                        Stream.of(
                                endpoint("POST configuration", HttpMethod.POST, ids -> "/interview-configurations", ids -> TECHNICAL_CONFIGURATION),
                                endpoint("GET configurations", HttpMethod.GET, ids -> "/interview-configurations"),
                                endpoint("GET interviews", HttpMethod.GET, ids -> "/interviews"),
                                endpoint("POST interview inline", HttpMethod.POST, ids -> "/interviews",
                                        ids -> "{\"configuration\":" + TECHNICAL_CONFIGURATION + "}")))
                .toList();
    }

    static List<Arguments> addressed() {
        return addressedEndpoints().stream().map(Arguments::of).toList();
    }

    static List<Arguments> every() {
        return allEndpoints().stream().map(Arguments::of).toList();
    }

    private static final String TECHNICAL_CONFIGURATION = """
            {"role":"BACKEND","experienceLevel":"MID","difficulty":"MEDIUM","durationMinutes":45,
             "interviewType":"TECHNICAL","skills":["java","spring-boot"]}""";

    private MockHttpServletRequestBuilder build(Endpoint endpoint, Ids ids) {
        MockHttpServletRequestBuilder builder = request(endpoint.method(), API + endpoint.path().apply(ids));
        if (endpoint.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(endpoint.body().apply(ids));
        }
        return builder;
    }

    /** What ada owns: a configuration, a started interview and its introduction question. */
    private Ids adasIds(UUID ada) throws Exception {
        UUID configuration = saveConfiguration(ada, TECHNICAL_CONFIGURATION);
        UUID interview = startedInterview(ada);
        InterviewQuestion question = questions.findByInterviewIdOrderBySequenceNoAsc(interview).getFirst();
        return new Ids(configuration, interview, question.getId());
    }

    // ---- ownership ---------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("addressed")
    void aResourceOfSomebodyElseAnswersExactlyLikeOneThatDoesNotExist(Endpoint endpoint) throws Exception {
        UUID ada = newUser();
        UUID bob = newUser();
        Ids adas = adasIds(ada);

        JsonNode foreign = body(mockMvc.perform(build(endpoint, adas).with(as(bob))).andExpect(status().isNotFound()));
        JsonNode missing = body(mockMvc.perform(build(endpoint, Ids.nonexistent()).with(as(bob))).andExpect(status().isNotFound()));

        assertThat(foreign.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(foreign.get("status")).isEqualTo(missing.get("status"));
        assertThat(foreign.get("title")).isEqualTo(missing.get("title"));
        assertThat(foreign.get("detail")).isEqualTo(missing.get("detail"));
        assertThat(foreign.get("type")).isEqualTo(missing.get("type"));
    }

    @Test
    void nothingOfAdasChangesWhileBobTriesEveryEndpointOnHerIds() throws Exception {
        UUID ada = newUser();
        UUID bob = newUser();
        Ids adas = adasIds(ada);
        Interview before = fixture.reload(adas.interview());
        long transitionsBefore = transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(adas.interview()).size();

        for (Endpoint endpoint : addressedEndpoints()) {
            mockMvc.perform(build(endpoint, adas).with(as(bob)));
        }

        Interview after = fixture.reload(adas.interview());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getState()).isEqualTo(InterviewState.INTRODUCTION);
        assertThat(transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(adas.interview())).hasSize((int) transitionsBefore);
        assertThat(configurations.findByIdAndOwnerUserId(adas.configuration(), ada)).isPresent();
        getAs(ada, "/interviews/" + adas.interview()).andExpect(status().isOk());
    }

    @Test
    void aQuestionOfAnotherInterviewIsNotFoundEvenForItsOwner() throws Exception {
        UUID ada = newUser();
        Ids first = adasIds(ada);
        UUID second = startedInterview(ada);

        getAs(ada, "/interviews/" + second + "/questions/" + first.question()).andExpect(status().isNotFound());
        getAs(ada, "/interviews/" + first.interview() + "/questions/" + first.question()).andExpect(status().isOk());
    }

    @Test
    void twoUsersWorkingAtTheSameTimeNeverSeeEachOthersInterviews() throws Exception {
        UUID ada = newUser();
        UUID bob = newUser();
        Ids adas = adasIds(ada);
        Ids bobs = adasIds(bob);

        getAs(ada, "/interviews/" + bobs.interview()).andExpect(status().isNotFound());
        getAs(bob, "/interviews/" + adas.interview()).andExpect(status().isNotFound());
        getAs(ada, "/interviews/" + adas.interview() + "/questions")
                .andExpect(jsonPath("$[0].id").value(adas.question().toString()));
        getAs(bob, "/interviews/" + bobs.interview() + "/questions")
                .andExpect(jsonPath("$[0].id").value(bobs.question().toString()));
        getAs(ada, "/interviews").andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].id").value(adas.interview().toString()));
        getAs(ada, "/interview-configurations").andExpect(jsonPath("$.totalItems").value(2));
        postAs(bob, "/interviews", "{\"configurationId\":\"" + adas.configuration() + "\"}").andExpect(status().isNotFound());
        deleteAs(bob, "/interview-configurations/" + adas.configuration()).andExpect(status().isNotFound());
        getAs(ada, "/interview-configurations/" + adas.configuration()).andExpect(status().isOk());
    }

    @Test
    void anAdministratorHasNoAccessToOthersInterviewsEither() throws Exception {
        UUID ada = newUser();
        UUID admin = newUser();
        Ids adas = adasIds(ada);

        mockMvc.perform(request(HttpMethod.GET, API + "/interviews/" + adas.interview()).with(auth.asUser(admin, Role.ADMIN)))
                .andExpect(status().isNotFound());
        mockMvc.perform(request(HttpMethod.GET, API + "/interviews").with(auth.asUser(admin, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    // ---- authentication -----------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("every")
    void aRequestWithoutATokenIsRefusedWithAChallenge(Endpoint endpoint) throws Exception {
        UUID ada = newUser();
        Ids adas = adasIds(ada);

        mockMvc.perform(build(endpoint, adas))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("every")
    void anExpiredTokenIsRefusedOnEveryEndpoint(Endpoint endpoint) throws Exception {
        UUID ada = newUser();
        Ids adas = adasIds(ada);

        mockMvc.perform(build(endpoint, adas).header("Authorization", "Bearer " + auth.expiredToken(ada, Role.CANDIDATE)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void aTokenThatForgesItsWayInIsRefusedOnEveryEndpoint() throws Exception {
        UUID ada = newUser();
        Ids adas = adasIds(ada);
        List<String> forged = List.of(
                auth.tamperedSignatureToken(ada, Role.CANDIDATE),
                auth.roleEscalatedToken(ada),
                auth.algNoneToken(ada, Role.CANDIDATE),
                auth.otherIssuerToken(ada, Role.CANDIDATE),
                auth.otherKeyToken(ada, Role.CANDIDATE),
                auth.rs256Token(ada, Role.CANDIDATE),
                auth.hs384Token(ada, Role.CANDIDATE),
                auth.withoutSubjectToken(Role.CANDIDATE),
                auth.withoutExpiryToken(ada, Role.CANDIDATE),
                "not-a-token");

        for (Endpoint endpoint : allEndpoints()) {
            for (String token : forged) {
                mockMvc.perform(build(endpoint, adas).header("Authorization", "Bearer " + token))
                        .andExpect(status().isUnauthorized());
            }
        }
    }

    @Test
    void theTokenIsOnlyReadFromTheAuthorizationHeader() throws Exception {
        UUID ada = newUser();
        String token = auth.token(ada, Role.CANDIDATE);

        mockMvc.perform(request(HttpMethod.GET, API + "/interviews?access_token=" + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(request(HttpMethod.GET, API + "/interviews").header("Authorization", "Basic " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenThatWasValidWhenIssuedStopsWorkingAtItsExpiry() throws Exception {
        UUID ada = newUser();
        String token = auth.token(ada, Role.CANDIDATE);
        mockMvc.perform(request(HttpMethod.GET, API + "/interviews").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(16));

        mockMvc.perform(request(HttpMethod.GET, API + "/interviews").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }
}
