package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.techinterviewer.interview.api.dto.AnswerFormat;
import com.techinterviewer.interview.api.dto.ConfigurationResponse;
import com.techinterviewer.interview.api.dto.CreateInterviewRequest;
import com.techinterviewer.interview.api.dto.InterviewResponse;
import com.techinterviewer.interview.api.dto.InterviewSummaryResponse;
import com.techinterviewer.interview.api.dto.ProgressResponse;
import com.techinterviewer.interview.api.dto.PublicPlan;
import com.techinterviewer.interview.api.dto.PublicSectionPlan;
import com.techinterviewer.interview.api.dto.QuestionResponse;
import com.techinterviewer.interview.api.dto.SessionResponse;
import com.techinterviewer.interview.api.dto.StateResponse;
import com.techinterviewer.interview.api.dto.TranscriptResponse;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.service.InterviewCreationService;
import com.techinterviewer.interview.service.InterviewLifecycleService;
import com.techinterviewer.interview.service.InterviewQueryService;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.security.WithApplicationSecurity;
import com.techinterviewer.user.Role;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The HTTP contract of the interview endpoints behind the real security chain, with the services replaced. */
@WebMvcTest(InterviewController.class)
@WithApplicationSecurity
class InterviewControllerTest {

    private static final String URL = "/api/v1/interviews";
    private static final UUID ADA = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d01");
    private static final UUID INTERVIEW = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0a");
    private static final UUID QUESTION = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0b");
    private static final UUID CONFIGURATION = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0c");

    private static final String INLINE = """
            {"configuration":{"role":"BACKEND","experienceLevel":"MID","difficulty":"MEDIUM","durationMinutes":45,
             "interviewType":"TECHNICAL","skills":["java"]}}""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestClock clock;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    InterviewCreationService creation;

    @MockitoBean
    InterviewLifecycleService lifecycle;

    @MockitoBean
    InterviewQueryService queries;

    TestAuth auth;

    @BeforeEach
    void setUp() {
        auth = TestAuth.forContext(context);
    }

    private static ConfigurationResponse configuration() {
        return new ConfigurationResponse(CONFIGURATION, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 45,
                InterviewType.TECHNICAL, List.of(), null, false, com.techinterviewer.interview.domain.InterviewStrategy.FUNDAMENTALS_FIRST,
                new com.techinterviewer.interview.api.dto.StageMixView(60, 25, 15), TestClock.START);
    }

    private static ProgressResponse progress() {
        return new ProgressResponse(Section.INTRODUCTION, 0, 2, 0, 1, 2700);
    }

    private static QuestionResponse question(boolean followUp) {
        return new QuestionResponse(QUESTION, 1, followUp ? QuestionKind.FOLLOW_UP : QuestionKind.INTRO, followUp, Section.INTRODUCTION,
                null, "Tell me about yourself.", AnswerFormat.TEXT, 4, null, false, null);
    }

    private static SessionResponse session() {
        return new SessionResponse(INTERVIEW, InterviewState.INTRODUCTION, TestClock.START, TestClock.START,
                TestClock.START.plusSeconds(2700), null, progress(), question(false), false, false, 2, configuration());
    }

    private MockHttpServletRequestBuilder asAda(MockHttpServletRequestBuilder request) {
        return request.with(auth.asUser(ADA, Role.CANDIDATE));
    }

    // ---- authentication -----------------------------------------------------------------------------------------

    static Stream<Arguments> everyEndpoint() {
        String id = INTERVIEW.toString();
        return Stream.of(
                Arguments.of("POST", URL), Arguments.of("GET", URL), Arguments.of("GET", URL + "/" + id),
                Arguments.of("GET", URL + "/" + id + "/state"), Arguments.of("POST", URL + "/" + id + "/start"),
                Arguments.of("GET", URL + "/" + id + "/questions"), Arguments.of("GET", URL + "/" + id + "/questions/current"),
                Arguments.of("GET", URL + "/" + id + "/questions/" + QUESTION), Arguments.of("POST", URL + "/" + id + "/abandon"),
                Arguments.of("GET", URL + "/" + id + "/transcript"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void everyEndpointRefusesARequestWithoutATokenAndNeverReachesAService(String method, String path) throws Exception {
        MockHttpServletRequestBuilder request = "POST".equals(method)
                ? post(path).contentType(MediaType.APPLICATION_JSON).content(INLINE)
                : get(path);

        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(creation, lifecycle, queries);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void anExpiredTokenIsRefusedOnEveryEndpoint(String method, String path) throws Exception {
        String token = auth.expiredToken(ADA, Role.CANDIDATE);
        MockHttpServletRequestBuilder request = "POST".equals(method)
                ? post(path).contentType(MediaType.APPLICATION_JSON).content(INLINE)
                : get(path);

        mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        verifyNoInteractions(creation, lifecycle, queries);
    }

    @Test
    void aTokenThatExpiresWhileTheClockAdvancesStopsWorkingAtItsExpiry() throws Exception {
        var bearer = auth.asUser(ADA, Role.CANDIDATE);
        when(queries.session(ADA, INTERVIEW)).thenReturn(session());

        mockMvc.perform(get(URL + "/" + INTERVIEW).with(bearer)).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(15));
        mockMvc.perform(get(URL + "/" + INTERVIEW).with(bearer)).andExpect(status().isUnauthorized());
    }

    // ---- create ---------------------------------------------------------------------------------------------------

    private InterviewResponse created() {
        return new InterviewResponse(INTERVIEW, InterviewState.INIT, configuration(),
                new PublicPlan(List.of(new PublicSectionPlan(Section.INTRODUCTION, 3, 1))), TestClock.START, 0);
    }

    @Test
    void anInterviewIsCreatedForTheUserOfTheTokenAndLocatedByItsId() throws Exception {
        when(creation.create(eq(ADA), any())).thenReturn(created());

        mockMvc.perform(asAda(post(URL)).contentType(MediaType.APPLICATION_JSON).content(INLINE))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", URL + "/" + INTERVIEW))
                .andExpect(jsonPath("$.id").value(INTERVIEW.toString()))
                .andExpect(jsonPath("$.state").value("INIT"))
                .andExpect(jsonPath("$.plan.sections[0].plannedQuestions").value(1))
                .andExpect(jsonPath("$.version").value(0));

        ArgumentCaptor<CreateInterviewRequest> sent = ArgumentCaptor.forClass(CreateInterviewRequest.class);
        verify(creation).create(eq(ADA), sent.capture());
        assertThat(sent.getValue().configurationId()).isNull();
        assertThat(sent.getValue().configuration().skills()).containsExactly("java");
    }

    @Test
    void aConfigurationIdIsPassedOnAsGiven() throws Exception {
        when(creation.create(eq(ADA), any())).thenReturn(created());

        mockMvc.perform(asAda(post(URL)).contentType(MediaType.APPLICATION_JSON).content("{\"configurationId\":\"" + CONFIGURATION + "\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateInterviewRequest> sent = ArgumentCaptor.forClass(CreateInterviewRequest.class);
        verify(creation).create(eq(ADA), sent.capture());
        assertThat(sent.getValue().configurationId()).isEqualTo(CONFIGURATION);
        assertThat(sent.getValue().configuration()).isNull();
    }

    @Test
    void anInvalidInlineConfigurationIsReportedOnItsNestedField() throws Exception {
        mockMvc.perform(asAda(post(URL)).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"configuration\":{\"role\":\"BACKEND\",\"experienceLevel\":\"MID\",\"difficulty\":\"MEDIUM\","
                                + "\"interviewType\":\"TECHNICAL\",\"skills\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='configuration.durationMinutes')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='configuration.skills')]").isNotEmpty());

        verifyNoInteractions(creation);
    }

    @Test
    void theCandidateCannotBeNamedInTheBodyAndAConfigurationIdMustBeAUuid() throws Exception {
        mockMvc.perform(asAda(post(URL)).contentType(MediaType.APPLICATION_JSON).content("{\"candidateId\":\"" + ADA + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        mockMvc.perform(asAda(post(URL)).contentType(MediaType.APPLICATION_JSON).content("{\"configurationId\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.errors[0].field").value("configurationId"));

        verifyNoInteractions(creation);
    }

    @Test
    void anEmptyObjectIsLeftToTheServiceToJudgeAndItsDailyLimitIsA429WithRetryAfter() throws Exception {
        when(creation.create(eq(ADA), any())).thenThrow(
                new ApiException(ErrorCode.DAILY_INTERVIEW_LIMIT, "limit").withRetryAfter(Duration.ofHours(2)));

        mockMvc.perform(asAda(post(URL)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7200"))
                .andExpect(jsonPath("$.code").value("DAILY_INTERVIEW_LIMIT"));
    }

    // ---- list -------------------------------------------------------------------------------------------------------

    @Test
    void theListIsPagedAndFilteredByStateForTheUserOfTheToken() throws Exception {
        InterviewSummaryResponse summary = new InterviewSummaryResponse(
                INTERVIEW, InterviewState.QUESTIONING, configuration(), TestClock.START, TestClock.START, null, null);
        when(queries.list(ADA, InterviewState.QUESTIONING, 1, 5)).thenReturn(new PageResponse<>(List.of(summary), 1, 5, 6, 2));

        mockMvc.perform(asAda(get(URL)).param("state", "QUESTIONING").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].state").value("QUESTIONING"))
                .andExpect(jsonPath("$.items[0].endedAt").value((Object) null))
                .andExpect(jsonPath("$.totalItems").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void withoutAStateTheListIsNotFiltered() throws Exception {
        when(queries.list(ADA, null, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(asAda(get(URL))).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        verify(queries).list(ADA, null, 0, 20);
    }

    @Test
    void anUnknownStateIsBadRequest() throws Exception {
        mockMvc.perform(asAda(get(URL)).param("state", "RUNNING")).andExpect(status().isBadRequest());

        verifyNoInteractions(queries);
    }

    @ParameterizedTest
    @CsvSource({"-1,20,page", "0,0,size", "0,101,size"})
    void aPageOrSizeOutOfRangeIsAFieldError(String page, String size, String field) throws Exception {
        mockMvc.perform(asAda(get(URL)).param("page", page).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));

        verifyNoInteractions(queries);
    }

    // ---- reads -------------------------------------------------------------------------------------------------------

    @Test
    void theSessionIsReadForTheUserOfTheToken() throws Exception {
        when(queries.session(ADA, INTERVIEW)).thenReturn(session());

        mockMvc.perform(asAda(get(URL + "/" + INTERVIEW)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(INTERVIEW.toString()))
                .andExpect(jsonPath("$.serverTime").value("2026-10-05T12:00:00Z"))
                .andExpect(jsonPath("$.progress.sectionCount").value(2))
                .andExpect(jsonPath("$.currentQuestion.id").value(QUESTION.toString()))
                .andExpect(jsonPath("$.currentQuestion.isFollowUp").value(false))
                .andExpect(jsonPath("$.currentQuestion.followUp").doesNotExist())
                .andExpect(jsonPath("$.currentQuestion.details").value((Object) null))
                .andExpect(jsonPath("$.awaitingNextQuestion").value(false))
                .andExpect(jsonPath("$.interviewComplete").value(false))
                .andExpect(jsonPath("$.endReason").value((Object) null));
    }

    @Test
    void theStateIsReadForTheUserOfTheToken() throws Exception {
        when(queries.state(ADA, INTERVIEW)).thenReturn(new StateResponse(
                InterviewState.QUESTIONING, progress(), TestClock.START.plusSeconds(60), TestClock.START, 4, true, false));

        mockMvc.perform(asAda(get(URL + "/" + INTERVIEW + "/state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUESTIONING"))
                .andExpect(jsonPath("$.awaitingNextQuestion").value(true))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.deadlineAt").value("2026-10-05T12:01:00Z"));
    }

    @Test
    void theQuestionsAreAnArrayInOrder() throws Exception {
        when(queries.questions(ADA, INTERVIEW)).thenReturn(List.of(question(false), question(true)));

        mockMvc.perform(asAda(get(URL + "/" + INTERVIEW + "/questions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].isFollowUp").value(false))
                .andExpect(jsonPath("$[1].isFollowUp").value(true));
    }

    @Test
    void theCurrentQuestionAndOneQuestionAreRoutedApartAndScopedToTheInterview() throws Exception {
        when(queries.currentQuestion(ADA, INTERVIEW)).thenReturn(question(false));
        when(queries.question(ADA, INTERVIEW, QUESTION)).thenReturn(question(true));

        mockMvc.perform(asAda(get(URL + "/" + INTERVIEW + "/questions/current")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isFollowUp").value(false));
        mockMvc.perform(asAda(get(URL + "/" + INTERVIEW + "/questions/" + QUESTION)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isFollowUp").value(true));
    }

    @Test
    void aMissingCurrentQuestionIsA404Problem() throws Exception {
        when(queries.currentQuestion(ADA, INTERVIEW)).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "none"));

        mockMvc.perform(asAda(get(URL + "/" + INTERVIEW + "/questions/current")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void theTranscriptIsReadForTheUserOfTheToken() throws Exception {
        when(queries.transcript(ADA, INTERVIEW)).thenReturn(new TranscriptResponse(INTERVIEW, InterviewState.FINAL_EVALUATION, List.of()));

        mockMvc.perform(asAda(get(URL + "/" + INTERVIEW + "/transcript")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interviewId").value(INTERVIEW.toString()))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    // ---- start and abandon ---------------------------------------------------------------------------------------

    @Test
    void startAndAbandonActForTheUserOfTheTokenWithoutABody() throws Exception {
        when(lifecycle.start(ADA, INTERVIEW)).thenReturn(session());
        when(lifecycle.abandon(ADA, INTERVIEW)).thenReturn(session());

        mockMvc.perform(asAda(post(URL + "/" + INTERVIEW + "/start"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INTRODUCTION"));
        mockMvc.perform(asAda(post(URL + "/" + INTERVIEW + "/abandon"))).andExpect(status().isOk());

        verify(lifecycle).start(ADA, INTERVIEW);
        verify(lifecycle).abandon(ADA, INTERVIEW);
    }

    @Test
    void anIllegalTransitionIsA409WithTheCurrentState() throws Exception {
        when(lifecycle.start(ADA, INTERVIEW)).thenThrow(
                new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, "no").withProperty("currentState", "QUESTIONING"));

        mockMvc.perform(asAda(post(URL + "/" + INTERVIEW + "/start")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentState").value("QUESTIONING"));
    }

    // ---- ids ----------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"GET,/not-a-uuid", "GET,/not-a-uuid/state", "POST,/not-a-uuid/start", "POST,/not-a-uuid/abandon",
            "GET,/not-a-uuid/questions", "GET,/not-a-uuid/questions/current", "GET,/not-a-uuid/transcript"})
    void aMalformedInterviewIdIsBadRequestWithoutReachingAService(String method, String path) throws Exception {
        MockHttpServletRequestBuilder request = "POST".equals(method) ? post(URL + path) : get(URL + path);

        mockMvc.perform(asAda(request)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(creation, lifecycle, queries);
    }

    @Test
    void aMalformedQuestionIdIsBadRequest() throws Exception {
        mockMvc.perform(asAda(get(URL + "/" + INTERVIEW + "/questions/42"))).andExpect(status().isBadRequest());

        verifyNoInteractions(queries);
    }

    @Test
    void interviewsCannotBeChangedOrDeletedThroughTheirUrl() throws Exception {
        mockMvc.perform(asAda(put(URL + "/" + INTERVIEW)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(asAda(delete(URL + "/" + INTERVIEW))).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(creation, lifecycle, queries);
    }
}
