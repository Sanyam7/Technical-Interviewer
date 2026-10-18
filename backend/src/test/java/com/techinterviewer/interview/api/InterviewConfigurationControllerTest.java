package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestClock;
import com.techinterviewer.common.api.PageResponse;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.interview.api.dto.ConfigurationRequest;
import com.techinterviewer.interview.api.dto.ConfigurationResponse;
import com.techinterviewer.interview.api.dto.SkillSummary;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.service.InterviewConfigurationService;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.security.WithApplicationSecurity;
import com.techinterviewer.user.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The HTTP contract of the configuration endpoints behind the real security chain, with the service replaced. */
@WebMvcTest(InterviewConfigurationController.class)
@WithApplicationSecurity
class InterviewConfigurationControllerTest {

    private static final String URL = "/api/v1/interview-configurations";
    private static final UUID ADA = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d01");
    private static final UUID CONFIGURATION = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d09");
    private static final String VALID = """
            {"role":"BACKEND","experienceLevel":"MID","difficulty":"MEDIUM","durationMinutes":45,
             "interviewType":"CODING","skills":["java","spring-boot"],"codingLanguage":"java","useResume":false}""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JsonMapper json;

    @MockitoBean
    InterviewConfigurationService service;

    TestAuth auth;

    @BeforeEach
    void setUp() {
        auth = TestAuth.forContext(context);
    }

    private static ConfigurationResponse response() {
        return new ConfigurationResponse(CONFIGURATION, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 45,
                InterviewType.CODING, List.of(new SkillSummary("java", "Java")), "java", false,
                com.techinterviewer.interview.domain.InterviewStrategy.FUNDAMENTALS_FIRST,
                new com.techinterviewer.interview.api.dto.StageMixView(60, 25, 15), TestClock.START);
    }

    private ResultActions postAs(UUID user, String body) throws Exception {
        return mockMvc.perform(post(URL).with(auth.asUser(user, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String with(String property, Object value) {
        ObjectNode node = (ObjectNode) json.readTree(VALID);
        if (value == null) {
            node.remove(property);
        } else {
            node.set(property, json.valueToTree(value));
        }
        return node.toString();
    }

    // ---- authentication ---------------------------------------------------------------------------------------

    @Test
    void everyEndpointRefusesARequestWithoutAToken() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/" + CONFIGURATION)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(URL + "/" + CONFIGURATION)).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    // ---- create -------------------------------------------------------------------------------------------------

    @Test
    void aValidConfigurationIsCreatedForTheUserOfTheTokenAndLocatedByItsId() throws Exception {
        when(service.create(eq(ADA), any())).thenReturn(response());

        postAs(ADA, VALID)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", URL + "/" + CONFIGURATION))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(CONFIGURATION.toString()))
                .andExpect(jsonPath("$.skills[0].slug").value("java"))
                .andExpect(jsonPath("$.useResume").value(false));

        ArgumentCaptor<ConfigurationRequest> sent = ArgumentCaptor.forClass(ConfigurationRequest.class);
        verify(service).create(eq(ADA), sent.capture());
        assertThat(sent.getValue()).isEqualTo(new ConfigurationRequest(
                TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 45, InterviewType.CODING,
                List.of("java", "spring-boot"), "java", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "experienceLevel", "difficulty", "durationMinutes", "interviewType", "skills"})
    void aMissingRequiredFieldIsAFieldError(String field) throws Exception {
        postAs(ADA, with(field, null))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value(field));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"role,CEO", "experienceLevel,GURU", "difficulty,IMPOSSIBLE", "interviewType,PAIRING", "role,backend"})
    void anUnknownEnumValueIsAMalformedRequestOnItsField(String field, String value) throws Exception {
        postAs(ADA, with(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.errors[0].field").value(field));

        verifyNoInteractions(service);
    }

    @Test
    void aTextWhereTheDurationShouldBeIsMalformed() throws Exception {
        postAs(ADA, with("durationMinutes", "45 minutes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void skillsMustHaveBetweenOneAndSixEntries() throws Exception {
        postAs(ADA, with("skills", List.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skills"));
        postAs(ADA, with("skills", List.of("a", "b", "c", "d", "e", "f", "g"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skills"));
        verifyNoInteractions(service);

        when(service.create(eq(ADA), any())).thenReturn(response());
        postAs(ADA, with("skills", List.of("a"))).andExpect(status().isCreated());
        postAs(ADA, with("skills", List.of("a", "b", "c", "d", "e", "f"))).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Java", "java script", "-java", "java-", "java--x", "ja/va", "", " "})
    void aSkillThatIsNotASlugIsAFieldErrorOnItsIndex(String slug) throws Exception {
        postAs(ADA, with("skills", List.of("java", slug)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skills[1]"));

        verifyNoInteractions(service);
    }

    @Test
    void aNullSkillEntryIsAFieldError() throws Exception {
        postAs(ADA, "{\"role\":\"BACKEND\",\"experienceLevel\":\"MID\",\"difficulty\":\"MEDIUM\",\"durationMinutes\":45,"
                + "\"interviewType\":\"TECHNICAL\",\"skills\":[null]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skills[0]"));
    }

    @Test
    void aSlugOfSixtyCharactersPassesAndOneOfSixtyOneDoesNot() throws Exception {
        when(service.create(eq(ADA), any())).thenReturn(response());

        postAs(ADA, with("skills", List.of("a".repeat(60)))).andExpect(status().isCreated());
        postAs(ADA, with("skills", List.of("a".repeat(61)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skills[0]"));
    }

    @Test
    void theCodingLanguageHasAMaximumLengthOfTwentyCharacters() throws Exception {
        when(service.create(eq(ADA), any())).thenReturn(response());

        postAs(ADA, with("codingLanguage", "x".repeat(20))).andExpect(status().isCreated());
        postAs(ADA, with("codingLanguage", "x".repeat(21))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("codingLanguage"));
    }

    @Test
    void theOwnerCannotBeNamedInTheBody() throws Exception {
        for (String property : List.of("ownerUserId", "userId", "id", "createdAt")) {
            postAs(ADA, with(property, ADA.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                    .andExpect(jsonPath("$.errors[0].field").value(property));
        }

        verifyNoInteractions(service);
    }

    @Test
    void anAbsentBodyAndABodyThatIsNotJsonAreMalformed() throws Exception {
        mockMvc.perform(post(URL).with(auth.asUser(ADA, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        postAs(ADA, "not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void aBodyThatIsNotJsonIsAnUnsupportedMediaType() throws Exception {
        mockMvc.perform(post(URL).with(auth.asUser(ADA, Role.CANDIDATE)).contentType(MediaType.TEXT_PLAIN).content(VALID))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void aValidationFailureOfTheServiceIsReportedOnItsFields() throws Exception {
        when(service.create(eq(ADA), any())).thenThrow(
                new FieldValidationException("skills[1]", "Unknown or inactive skill 'cobol'."));

        postAs(ADA, VALID)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("skills[1]"));
    }

    // ---- list -----------------------------------------------------------------------------------------------------

    @Test
    void theListDefaultsToTheFirstPageOfTwentyAndAsksForTheUserOfTheToken() throws Exception {
        when(service.list(ADA, 0, 20)).thenReturn(new PageResponse<>(List.of(response()), 0, 20, 1, 1));

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void thePageAndSizeAreTakenFromTheQuery() throws Exception {
        when(service.list(ADA, 2, 100)).thenReturn(new PageResponse<>(List.of(), 2, 100, 0, 0));

        mockMvc.perform(get(URL).param("page", "2").param("size", "100").with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk());
        verify(service).list(ADA, 2, 100);
    }

    @ParameterizedTest
    @CsvSource({"-1,20,page", "0,0,size", "0,-5,size", "0,101,size"})
    void aPageOrSizeOutOfRangeIsAFieldError(String page, String size, String field) throws Exception {
        mockMvc.perform(get(URL).param("page", page).param("size", size).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field));

        verifyNoInteractions(service);
    }

    @Test
    void aPageThatIsNotANumberIsBadRequest() throws Exception {
        mockMvc.perform(get(URL).param("page", "first").with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ---- one and delete ---------------------------------------------------------------------------------------

    @Test
    void oneConfigurationIsReadForTheUserOfTheToken() throws Exception {
        when(service.get(ADA, CONFIGURATION)).thenReturn(response());

        mockMvc.perform(get(URL + "/" + CONFIGURATION).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONFIGURATION.toString()));
    }

    @Test
    void aMissingConfigurationIsA404Problem() throws Exception {
        when(service.get(ADA, CONFIGURATION)).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "Configuration not found."));

        mockMvc.perform(get(URL + "/" + CONFIGURATION).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anIdThatIsNotAUuidIsBadRequestWithoutReachingTheService() throws Exception {
        mockMvc.perform(get(URL + "/not-a-uuid").with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mockMvc.perform(delete(URL + "/42").with(auth.asUser(ADA, Role.CANDIDATE))).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void deletingAnswers204WithoutABodyForTheUserOfTheToken() throws Exception {
        mockMvc.perform(delete(URL + "/" + CONFIGURATION).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete(ADA, CONFIGURATION);
    }

    @Test
    void aConfigurationInUseIsA409Problem() throws Exception {
        doThrow(new ApiException(ErrorCode.CONFIGURATION_IN_USE, "in use")).when(service).delete(ADA, CONFIGURATION);

        mockMvc.perform(delete(URL + "/" + CONFIGURATION).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIGURATION_IN_USE"));
    }

    @Test
    void thereIsNoWayToChangeAConfiguration() throws Exception {
        mockMvc.perform(put(URL + "/" + CONFIGURATION)
                        .with(auth.asUser(ADA, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch(URL + "/" + CONFIGURATION)
                        .with(auth.asUser(ADA, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(service);
    }
}
