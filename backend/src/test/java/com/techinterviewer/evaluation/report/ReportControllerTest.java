package com.techinterviewer.evaluation.report;

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
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.report.dto.CoverageResponse;
import com.techinterviewer.evaluation.report.dto.EvidenceResponse;
import com.techinterviewer.evaluation.report.dto.IntegrityNoteResponse;
import com.techinterviewer.evaluation.report.dto.NarrativeItemResponse;
import com.techinterviewer.evaluation.report.dto.ReadingResponse;
import com.techinterviewer.evaluation.report.dto.ReportResponse;
import com.techinterviewer.evaluation.report.dto.SectionScoreResponse;
import com.techinterviewer.evaluation.report.dto.SkillScoreResponse;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.security.WithApplicationSecurity;
import com.techinterviewer.user.Role;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract of the report endpoints behind the real security chain, with the service replaced. */
@WebMvcTest(ReportController.class)
@WithApplicationSecurity
class ReportControllerTest {

    private static final UUID ADA = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d01");
    private static final UUID GRACE = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d02");
    private static final UUID INTERVIEW = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d10");
    private static final UUID QUESTION = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d11");
    private static final String URL = "/api/v1/interviews/" + INTERVIEW + "/report";
    private static final String RETRY_URL = URL + "/retry";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestClock clock;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    ReportService service;

    TestAuth auth;

    @BeforeEach
    void setUp() {
        auth = TestAuth.forContext(context);
    }

    private static ReportResponse ready() {
        return new ReportResponse(
                INTERVIEW, ReportStatus.READY, com.techinterviewer.evaluation.domain.ReportOutcome.COMPLETE, 72.5, Recommendation.PROFICIENT,
                List.of(new SectionScoreResponse(Section.QUESTIONING, 76.0)),
                List.of(new SkillScoreResponse("Spring Boot", "spring-boot", 81.0, Confidence.HIGH, 4, 3.25)),
                "A summary.",
                List.of(new NarrativeItemResponse("Clear on proxies", List.of(new EvidenceResponse(QUESTION, "Transactions are applied...")))),
                List.of(new NarrativeItemResponse("Index selection", List.of())),
                List.of("Read the planner docs"),
                List.of(new ReadingResponse("Spring transactions", "Spring transactions › Propagation", "spring-boot/transactions.md")),
                List.of(new IntegrityNoteResponse(EvaluationFlag.INJECTION_SUSPECTED, QUESTION, "A note.")),
                new CoverageResponse(9, 1, 3, 43), new com.techinterviewer.evaluation.report.dto.AssessmentResponse(1.0, 0, 0, List.of()),
                new com.techinterviewer.evaluation.report.dto.KnowledgeGapsResponse(
                        List.of(new com.techinterviewer.evaluation.report.dto.MissedConceptResponse("Rollback rules for checked exceptions",
                                com.techinterviewer.evaluation.domain.KeyPointStatus.MISSING,
                                new com.techinterviewer.evaluation.report.dto.GapTopicResponse("spring-boot/transactions", "Spring Boot / Transactions"),
                                QUESTION)),
                        List.of(), List.of(), List.of()),
                null, TestClock.START, 1, false, null);
    }

    private static ReportResponse inProgress(ReportStatus status) {
        return new ReportResponse(INTERVIEW, status, null, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, status == ReportStatus.PENDING ? 0 : 1, status == ReportStatus.PENDING, null);
    }

    private static ReportResponse failed() {
        return new ReportResponse(INTERVIEW, ReportStatus.FAILED, null, null, null, List.of(), List.of(), null, List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null, null, null, null, 2, true, "2 answers still await evaluation. Try again shortly.");
    }

    // ---- authentication ---------------------------------------------------------------------------------

    @Test
    void withoutATokenBothEndpointsAreUnauthorizedAndNeverReachTheService() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post(RETRY_URL)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(service);
    }

    @Test
    void anExpiredTokenIsRefusedAsTokenExpired() throws Exception {
        String token = auth.expiredToken(ADA, Role.CANDIDATE);

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        mockMvc.perform(post(RETRY_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void aTokenStopsWorkingWhenItsFifteenMinutesAreOver() throws Exception {
        var bearer = auth.asUser(ADA, Role.CANDIDATE);
        when(service.get(ADA, INTERVIEW)).thenReturn(ready());

        mockMvc.perform(get(URL).with(bearer)).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(15));
        mockMvc.perform(get(URL).with(bearer)).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedTokensAreRefused() throws Exception {
        for (String token : List.of(
                auth.tamperedSignatureToken(ADA, Role.CANDIDATE),
                auth.algNoneToken(ADA, Role.CANDIDATE),
                auth.otherKeyToken(ADA, Role.CANDIDATE),
                auth.otherIssuerToken(ADA, Role.CANDIDATE))) {
            mockMvc.perform(get(URL).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
            mockMvc.perform(post(RETRY_URL).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    // ---- who is asking -------------------------------------------------------------------------------------

    @Test
    void theServiceIsAskedAboutTheTokenOwnerAndTheInterviewOfThePath() throws Exception {
        when(service.get(ADA, INTERVIEW)).thenReturn(ready());
        when(service.retry(GRACE, INTERVIEW)).thenReturn(failed());

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE))).andExpect(status().isOk());
        mockMvc.perform(post(RETRY_URL).with(auth.asUser(GRACE, Role.CANDIDATE))).andExpect(status().isOk());

        verify(service).get(ADA, INTERVIEW);
        verify(service).retry(GRACE, INTERVIEW);
        verify(service, never()).get(GRACE, INTERVIEW);
    }

    @Test
    void anAdminTokenIsTreatedLikeAnyOtherUserAndNotGivenAccessToEveryReport() throws Exception {
        when(service.get(ADA, INTERVIEW)).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "Interview not found."));

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(service).get(ADA, INTERVIEW);
    }

    @Test
    void aUserIdInTheRequestCannotSelectAnotherUsersReport() throws Exception {
        when(service.get(ADA, INTERVIEW)).thenReturn(ready());

        mockMvc.perform(get(URL).param("userId", GRACE.toString()).param("candidateId", GRACE.toString()).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk());

        verify(service).get(ADA, INTERVIEW);
        verify(service, never()).get(GRACE, INTERVIEW);
    }

    // ---- the status codes ----------------------------------------------------------------------------------

    @Test
    void aReadyReportIs200WithEveryDocumentedField() throws Exception {
        when(service.get(ADA, INTERVIEW)).thenReturn(ready());

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.interviewId").value(INTERVIEW.toString()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.overallScore").value(72.5))
                .andExpect(jsonPath("$.recommendation").value("PROFICIENT"))
                .andExpect(jsonPath("$.sections[0].section").value("QUESTIONING"))
                .andExpect(jsonPath("$.sections[0].score").value(76.0))
                .andExpect(jsonPath("$.skills[0].skill").value("Spring Boot"))
                .andExpect(jsonPath("$.skills[0].slug").value("spring-boot"))
                .andExpect(jsonPath("$.skills[0].score").value(81.0))
                .andExpect(jsonPath("$.skills[0].confidence").value("HIGH"))
                .andExpect(jsonPath("$.skills[0].evidenceCount").value(4))
                .andExpect(jsonPath("$.skills[0].avgDifficulty").value(3.25))
                .andExpect(jsonPath("$.summary").value("A summary."))
                .andExpect(jsonPath("$.strengths[0].text").value("Clear on proxies"))
                .andExpect(jsonPath("$.strengths[0].evidence[0].questionId").value(QUESTION.toString()))
                .andExpect(jsonPath("$.strengths[0].evidence[0].excerpt").value("Transactions are applied..."))
                .andExpect(jsonPath("$.improvementAreas[0].text").value("Index selection"))
                .andExpect(jsonPath("$.studyPlan[0]").value("Read the planner docs"))
                .andExpect(jsonPath("$.integrityNotes[0].flag").value("INJECTION_SUSPECTED"))
                .andExpect(jsonPath("$.integrityNotes[0].questionId").value(QUESTION.toString()))
                .andExpect(jsonPath("$.integrityNotes[0].note").value("A note."))
                .andExpect(jsonPath("$.coverage.questionsAnswered").value(9))
                .andExpect(jsonPath("$.coverage.skipped").value(1))
                .andExpect(jsonPath("$.coverage.followUps").value(3))
                .andExpect(jsonPath("$.coverage.minutesUsed").value(43))
                .andExpect(jsonPath("$.knowledgeGaps.missedConcepts[0].concept").value("Rollback rules for checked exceptions"))
                .andExpect(jsonPath("$.knowledgeGaps.missedConcepts[0].status").value("MISSING"))
                .andExpect(jsonPath("$.knowledgeGaps.missedConcepts[0].topic.name").value("Spring Boot / Transactions"))
                .andExpect(jsonPath("$.knowledgeGaps.repeatedMistakes").isEmpty())
                .andExpect(jsonPath("$.generatedAt").value("2026-10-05T12:00:00Z"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.errorMessage").value((Object) null));
    }

    @Test
    void aReportThatIsPendingOrGeneratingIs202() throws Exception {
        when(service.get(ADA, INTERVIEW)).thenReturn(inProgress(ReportStatus.PENDING));
        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.overallScore").value((Object) null));

        when(service.get(ADA, INTERVIEW)).thenReturn(inProgress(ReportStatus.GENERATING));
        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void aFailedReportIs200AndSaysWhyAndThatItCanBeRetried() throws Exception {
        when(service.get(ADA, INTERVIEW)).thenReturn(failed());

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("2 answers still await evaluation. Try again shortly."))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.overallScore").value((Object) null))
                .andExpect(jsonPath("$.sections").isEmpty())
                .andExpect(jsonPath("$.skills").isEmpty());
    }

    @Test
    void retryReturnsTheReportInTheSameWayAsGet() throws Exception {
        when(service.retry(ADA, INTERVIEW)).thenReturn(ready());
        mockMvc.perform(post(RETRY_URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.overallScore").value(72.5));

        when(service.retry(ADA, INTERVIEW)).thenReturn(failed());
        mockMvc.perform(post(RETRY_URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.retryable").value(true));

        when(service.retry(ADA, INTERVIEW)).thenReturn(inProgress(ReportStatus.GENERATING));
        mockMvc.perform(post(RETRY_URL).with(auth.asUser(ADA, Role.CANDIDATE))).andExpect(status().isAccepted());
    }

    @Test
    void problemsOfTheServiceAreProblemJsonWithTheirStatus() throws Exception {
        when(service.get(ADA, INTERVIEW)).thenThrow(
                new ApiException(ErrorCode.INTERVIEW_NOT_FINISHED, "The interview has not finished.").withProperty("currentState", "QUESTIONING"));
        when(service.retry(ADA, INTERVIEW)).thenThrow(
                new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, "Not retryable.").withProperty("currentState", "COMPLETED"));

        mockMvc.perform(get(URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERVIEW_NOT_FINISHED"))
                .andExpect(jsonPath("$.currentState").value("QUESTIONING"));
        mockMvc.perform(post(RETRY_URL).with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentState").value("COMPLETED"));
    }

    // ---- the request ------------------------------------------------------------------------------------------

    @Test
    void anInterviewIdThatIsNotAUuidIsABadRequestAndNeverReachesTheService() throws Exception {
        mockMvc.perform(get("/api/v1/interviews/not-a-uuid/report").with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(post("/api/v1/interviews/not-a-uuid/report/retry").with(auth.asUser(ADA, Role.CANDIDATE)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void theReportCannotBeChangedByTheClient() throws Exception {
        var candidate = auth.asUser(ADA, Role.CANDIDATE);

        mockMvc.perform(put(URL).with(candidate).contentType(MediaType.APPLICATION_JSON).content("{\"overallScore\":100}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post(URL).with(candidate).contentType(MediaType.APPLICATION_JSON).content("{\"overallScore\":100}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).with(candidate)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get(RETRY_URL).with(candidate)).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(service);
    }

    @Test
    void aBodySentToRetryIsIgnoredBecauseNothingInItCanChangeTheReport() throws Exception {
        when(service.retry(ADA, INTERVIEW)).thenReturn(ready());

        mockMvc.perform(post(RETRY_URL).with(auth.asUser(ADA, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overallScore\":100,\"recommendation\":\"STRONG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(72.5));

    }
}
