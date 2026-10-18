package com.techinterviewer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestClock;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.profile.dto.ProfileResponse;
import com.techinterviewer.profile.dto.ProfileSkillRequest;
import com.techinterviewer.profile.dto.ProfileSkillResponse;
import com.techinterviewer.profile.dto.UpdateProfileRequest;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.security.WithApplicationSecurity;
import com.techinterviewer.user.Role;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The HTTP contract of the profile endpoints behind the real security chain, with the service replaced. */
@WebMvcTest(ProfileController.class)
@WithApplicationSecurity
class ProfileControllerTest {

    private static final String URL = "/api/v1/profile";
    private static final UUID ADA = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d01");
    private static final UUID GRACE = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d02");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestClock clock;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    ProfileService service;

    TestAuth auth;

    @BeforeEach
    void setUp() {
        auth = TestAuth.forContext(context);
    }

    private static ProfileResponse profileOf(String headline, String skill, String name, int rating) {
        return new ProfileResponse(headline, 5, "Engineer", List.of(new ProfileSkillResponse(skill, name, rating)), TestClock.START);
    }

    private ResultActions putAs(UUID user, String body) throws Exception {
        return mockMvc.perform(put(URL).with(auth.asUser(user, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- authentication -----------------------------------------------------------------------------------

    @Test
    void withoutATokenEveryProfileRequestIsUnauthorizedAndNeverReachesTheService() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(validBody())).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void anExpiredTokenIsRefusedAsTokenExpired() throws Exception {
        String token = auth.expiredToken(ADA, Role.CANDIDATE);

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void aTokenThatExpiresWhileTheClockAdvancesStopsWorkingAtItsExpiry() throws Exception {
        RequestPostProcessor bearer = auth.asUser(ADA, Role.CANDIDATE);
        when(service.get(ADA)).thenReturn(profileOf("h", "java", "Java", 3));

        mockMvc.perform(get(URL).with(bearer)).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(15));
        mockMvc.perform(get(URL).with(bearer)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void forgedTokensAreRefused() throws Exception {
        for (String token : List.of(
                auth.tamperedSignatureToken(ADA, Role.CANDIDATE),
                auth.algNoneToken(ADA, Role.CANDIDATE),
                auth.otherKeyToken(ADA, Role.CANDIDATE),
                auth.otherIssuerToken(ADA, Role.CANDIDATE),
                auth.roleEscalatedToken(ADA))) {
            mockMvc.perform(get(URL).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    // ---- ownership ----------------------------------------------------------------------------------------

    @Test
    void getReturnsTheProfileOfTheTokenOwnerWhoeverElseHasOne() throws Exception {
        when(service.get(ADA)).thenReturn(profileOf("Ada's headline", "java", "Java", 5));
        when(service.get(GRACE)).thenReturn(profileOf("Grace's headline", "react", "React", 2));

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Ada's headline"))
                .andExpect(jsonPath("$.skills[0].skill").value("java"));
        mockMvc.perform(get(URL).with(auth.asUser(GRACE, Role.CANDIDATE)))
                .andExpect(jsonPath("$.headline").value("Grace's headline"))
                .andExpect(jsonPath("$.skills[0].skill").value("react"));
        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(jsonPath("$.headline").value("Ada's headline"));
    }

    @Test
    void putActsOnTheProfileOfTheTokenOwnerAndPassesTheBodyThrough() throws Exception {
        when(service.replace(eq(ADA), any())).thenReturn(profileOf("stored for Ada", "java", "Java", 4));
        when(service.replace(eq(GRACE), any())).thenReturn(profileOf("stored for Grace", "react", "React", 3));

        putAs(ADA, validBody()).andExpect(status().isOk()).andExpect(jsonPath("$.headline").value("stored for Ada"));
        putAs(GRACE, validBody()).andExpect(status().isOk()).andExpect(jsonPath("$.headline").value("stored for Grace"));

        ArgumentCaptor<UpdateProfileRequest> sent = ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(service).replace(eq(ADA), sent.capture());
        assertThat(sent.getValue()).isEqualTo(new UpdateProfileRequest(
                "Backend engineer", 7, "Staff Engineer", List.of(new ProfileSkillRequest("java", 4), new ProfileSkillRequest("spring-boot", 5))));
    }

    @Test
    void anIdInThePathOrBodyCannotSelectAnotherProfile() throws Exception {
        mockMvc.perform(get(URL + "/" + GRACE).with(auth.asUser(ADA, Role.CANDIDATE))).andExpect(status().isNotFound());
        putAs(ADA, "{\"userId\":\"" + GRACE + "\",\"skills\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));

        verifyNoInteractions(service);
    }

    @Test
    void anAdminTokenStillOnlyReachesItsOwnProfile() throws Exception {
        when(service.get(ADA)).thenReturn(profileOf("admin's own", "java", "Java", 1));

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.ADMIN))).andExpect(jsonPath("$.headline").value("admin's own"));
        verify(service).get(ADA);
        verify(service, never()).get(GRACE);
    }

    // ---- response shape -------------------------------------------------------------------------------------

    @Test
    void theResponseCarriesSlugNameAndRatingOfEachSkillAndNothingInternal() throws Exception {
        when(service.get(ADA)).thenReturn(new ProfileResponse(null, null, null, List.of(), TestClock.START));

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.headline").value((Object) null))
                .andExpect(jsonPath("$.yearsExperience").value((Object) null))
                .andExpect(jsonPath("$.jobTitle").value((Object) null))
                .andExpect(jsonPath("$.skills").isEmpty())
                .andExpect(jsonPath("$.updatedAt").value("2026-10-05T12:00:00Z"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.resumeDocumentId").doesNotExist());

        when(service.get(ADA)).thenReturn(profileOf("h", "spring-boot", "Spring Boot", 4));
        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(jsonPath("$.skills[0].skill").value("spring-boot"))
                .andExpect(jsonPath("$.skills[0].name").value("Spring Boot"))
                .andExpect(jsonPath("$.skills[0].selfRating").value(4))
                .andExpect(jsonPath("$.skills[0].id").doesNotExist());
    }

    @Test
    void methodsOtherThanGetAndPutAreNotAllowed() throws Exception {
        mockMvc.perform(delete(URL).with(auth.asUser(ADA, Role.CANDIDATE))).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post(URL).with(auth.asUser(ADA, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---- validation -----------------------------------------------------------------------------------------

    private static String validBody() {
        return """
                {"headline":"Backend engineer","yearsExperience":7,"jobTitle":"Staff Engineer",
                 "skills":[{"skill":"java","selfRating":4},{"skill":"spring-boot","selfRating":5}]}""";
    }

    private static String body(String headline, String years, String jobTitle, String skills) {
        return "{\"headline\":" + headline + ",\"yearsExperience\":" + years + ",\"jobTitle\":" + jobTitle + ",\"skills\":" + skills + "}";
    }

    private static String quoted(int length) {
        return "\"" + "x".repeat(length) + "\"";
    }

    private static String skills(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"skill\":\"skill-" + i + "\",\"selfRating\":3}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("headline of 121 characters", body(quoted(121), "5", "\"t\"", "[]"), "headline"),
                Arguments.of("job title of 61 characters", body("\"h\"", "5", quoted(61), "[]"), "jobTitle"),
                Arguments.of("negative years", body("\"h\"", "-1", "\"t\"", "[]"), "yearsExperience"),
                Arguments.of("61 years", body("\"h\"", "61", "\"t\"", "[]"), "yearsExperience"),
                Arguments.of("skills missing", "{\"headline\":\"h\"}", "skills"),
                Arguments.of("skills null", body("\"h\"", "5", "\"t\"", "null"), "skills"),
                Arguments.of("21 skills", body("\"h\"", "5", "\"t\"", skills(21)), "skills"),
                Arguments.of("null skill entry", body("\"h\"", "5", "\"t\"", "[null]"), "skills[0]"),
                Arguments.of("skill missing", body("\"h\"", "5", "\"t\"", "[{\"selfRating\":3}]"), "skills[0].skill"),
                Arguments.of("skill null", body("\"h\"", "5", "\"t\"", "[{\"skill\":null,\"selfRating\":3}]"), "skills[0].skill"),
                Arguments.of("blank skill", body("\"h\"", "5", "\"t\"", "[{\"skill\":\" \",\"selfRating\":3}]"), "skills[0].skill"),
                Arguments.of("upper-case skill", body("\"h\"", "5", "\"t\"", "[{\"skill\":\"Java\",\"selfRating\":3}]"), "skills[0].skill"),
                Arguments.of("skill with a slash", body("\"h\"", "5", "\"t\"", "[{\"skill\":\"a/b\",\"selfRating\":3}]"), "skills[0].skill"),
                Arguments.of("skill of 61 characters",
                        body("\"h\"", "5", "\"t\"", "[{\"skill\":\"" + "a".repeat(61) + "\",\"selfRating\":3}]"), "skills[0].skill"),
                Arguments.of("rating missing", body("\"h\"", "5", "\"t\"", "[{\"skill\":\"java\"}]"), "skills[0].selfRating"),
                Arguments.of("rating 0", body("\"h\"", "5", "\"t\"", "[{\"skill\":\"java\",\"selfRating\":0}]"), "skills[0].selfRating"),
                Arguments.of("rating 6", body("\"h\"", "5", "\"t\"", "[{\"skill\":\"java\",\"selfRating\":6}]"), "skills[0].selfRating"),
                Arguments.of("rating negative", body("\"h\"", "5", "\"t\"", "[{\"skill\":\"java\",\"selfRating\":-3}]"), "skills[0].selfRating"),
                Arguments.of("second skill invalid", body("\"h\"", "5", "\"t\"",
                        "[{\"skill\":\"java\",\"selfRating\":3},{\"skill\":\"react\",\"selfRating\":7}]"), "skills[1].selfRating"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidBodiesAreRejectedWithAFieldLevelProblemAndNeverReachTheService(String name, String body, String field) throws Exception {
        putAs(ADA, body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "')]").isNotEmpty());

        verifyNoInteractions(service);
    }

    static Stream<Arguments> validBodies() {
        return Stream.of(
                Arguments.of("minimum: no text, no years, no skills", body("null", "null", "null", "[]")),
                Arguments.of("empty body fields omitted", "{\"skills\":[]}"),
                Arguments.of("headline of 120 characters", body(quoted(120), "5", "\"t\"", "[]")),
                Arguments.of("job title of 60 characters", body("\"h\"", "5", quoted(60), "[]")),
                Arguments.of("zero years", body("\"h\"", "0", "\"t\"", "[]")),
                Arguments.of("sixty years", body("\"h\"", "60", "\"t\"", "[]")),
                Arguments.of("twenty skills", body("\"h\"", "5", "\"t\"", skills(20))),
                Arguments.of("rating 1", body("\"h\"", "5", "\"t\"", "[{\"skill\":\"java\",\"selfRating\":1}]")),
                Arguments.of("rating 5", body("\"h\"", "5", "\"t\"", "[{\"skill\":\"spring-boot\",\"selfRating\":5}]")),
                Arguments.of("skill slug of 60 characters",
                        body("\"h\"", "5", "\"t\"", "[{\"skill\":\"" + "a".repeat(60) + "\",\"selfRating\":3}]")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validBodies")
    void boundaryValuesAreAccepted(String name, String body) throws Exception {
        when(service.replace(eq(ADA), any())).thenReturn(profileOf("ok", "java", "Java", 3));

        putAs(ADA, body).andExpect(status().isOk());
        verify(service).replace(eq(ADA), any());
    }

    @Test
    void everyViolationOfOneRequestIsReportedTogether() throws Exception {
        putAs(ADA, body(quoted(121), "99", quoted(61), "[{\"skill\":\"java\",\"selfRating\":9}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.errors[?(@.field=='headline')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='jobTitle')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='yearsExperience')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='skills[0].selfRating')]").isNotEmpty());
    }

    @Test
    void unknownPropertiesAndResumeFieldsAreRejected() throws Exception {
        putAs(ADA, "{\"skills\":[],\"resumeDocumentId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.errors[0].field").value("resumeDocumentId"));
        putAs(ADA, "{\"skills\":[{\"skill\":\"java\",\"selfRating\":3,\"id\":4}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));

        verifyNoInteractions(service);
    }

    @Test
    void wrongTypesMalformedJsonAndOtherContentTypesAreClientErrors() throws Exception {
        putAs(ADA, "{\"skills\":[],\"yearsExperience\":\"seven\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        putAs(ADA, "{\"skills\":").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        putAs(ADA, "").andExpect(status().isBadRequest());
        mockMvc.perform(put(URL).with(auth.asUser(ADA, Role.CANDIDATE)).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(service);
    }

    @Test
    void aFieldValidationFailureOfTheServiceIsAnswered400WithItsFieldErrors() throws Exception {
        when(service.replace(eq(ADA), any())).thenThrow(new FieldValidationException("skills[1].skill", "Unknown or inactive skill 'cobol'."));

        putAs(ADA, validBody())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("skills[1].skill"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown or inactive skill 'cobol'."));
    }
}
