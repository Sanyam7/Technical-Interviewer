package com.techinterviewer.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.common.logging.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Boots the real MVC infrastructure with the real application.yml (Jackson settings included) but only
 * the controller advice and a test-only controller, so no database is needed.
 */
@WebMvcTest({ErrorTestController.class, ValidatedParamTestController.class})
@ActiveProfiles(ErrorTestController.PROFILE)
class GlobalExceptionHandlerTest {

    private static final String TRACE_ID = "trace-id-for-tests";
    private static final String TYPE_BASE = "https://techinterviewer.dev/problems/";

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new CorrelationIdFilter()).build();
    }

    private ResultActions problem(ResultActions result, int status, String code, String slug) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_BASE + slug))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(header().string("X-Request-Id", TRACE_ID));
    }

    private static MockHttpServletRequestBuilder traced(
            MockHttpServletRequestBuilder builder) {
        return builder.header("X-Request-Id", TRACE_ID);
    }

    @Test
    void invalidBodyFieldsAreListedSortedByField() throws Exception {
        ResultActions result = mockMvc.perform(traced(post("/test/body"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \",\"age\":3,\"tags\":[\"a\",\"b\",\"c\"]}"));

        problem(result, 400, "VALIDATION_FAILED", "validation-failed")
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("age"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.errors[1].field").value("name"))
                .andExpect(jsonPath("$.errors[2].field").value("tags"));
    }

    @Test
    void invalidRequestParameterIsReportedByParameterName() throws Exception {
        problem(mockMvc.perform(traced(get("/test/param")).param("count", "0")), 400, "VALIDATION_FAILED", "validation-failed")
                .andExpect(jsonPath("$.errors[0].field").value("count"));
    }

    @Test
    void constraintViolationsDropTheMethodNameFromTheFieldPath() throws Exception {
        problem(mockMvc.perform(traced(get("/test/constraint"))), 400, "VALIDATION_FAILED", "validation-failed")
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void malformedJsonIsRejectedWithoutEchoingTheParserMessage() throws Exception {
        problem(mockMvc.perform(traced(post("/test/body")).contentType(MediaType.APPLICATION_JSON).content("{\"name\": ")),
                400, "MALFORMED_JSON", "malformed-json")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(content().string(not(containsString("Unexpected"))))
                .andExpect(content().string(not(containsString("JsonParseException"))));
    }

    @Test
    void unknownJsonPropertyIsRejectedAndNamed() throws Exception {
        problem(mockMvc.perform(traced(post("/test/body")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada\",\"age\":30,\"role\":\"ADMIN\"}")),
                400, "MALFORMED_JSON", "malformed-json")
                .andExpect(jsonPath("$.errors[0].field").value("role"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown property"));
    }

    @Test
    void wrongJsonTypeNamesTheOffendingField() throws Exception {
        problem(mockMvc.perform(traced(post("/test/body")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada\",\"age\":\"thirty\"}")),
                400, "MALFORMED_JSON", "malformed-json")
                .andExpect(jsonPath("$.errors[0].field").value("age"))
                .andExpect(jsonPath("$.errors[0].message").value("Invalid value or type"));
    }

    @Test
    void parameterOfTheWrongTypeIsABadRequest() throws Exception {
        problem(mockMvc.perform(traced(get("/test/typed")).param("number", "abc")), 400, "BAD_REQUEST", "bad-request");
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        problem(mockMvc.perform(traced(get("/test/does-not-exist"))), 404, "NOT_FOUND", "not-found");
    }

    @Test
    void wrongMethodIsMethodNotAllowed() throws Exception {
        problem(mockMvc.perform(traced(get("/test/body"))), 405, "METHOD_NOT_ALLOWED", "method-not-allowed");
    }

    @Test
    void wrongContentTypeIsUnsupportedMediaType() throws Exception {
        problem(mockMvc.perform(traced(post("/test/body")).contentType(MediaType.TEXT_PLAIN).content("hello")),
                415, "UNSUPPORTED_MEDIA_TYPE", "unsupported-media-type");
    }

    @Test
    void apiExceptionKeepsItsCodeAndDetail() throws Exception {
        problem(mockMvc.perform(traced(get("/test/api-error"))), 409, "CONFLICT", "conflict")
                .andExpect(jsonPath("$.detail").value("The interview was modified in another tab."));
    }

    @Test
    void serviceUnavailableApiExceptionDoesNotLeakItsCause() throws Exception {
        problem(mockMvc.perform(traced(get("/test/provider-down"))), 503, "AI_PROVIDER_UNAVAILABLE", "ai-provider-unavailable")
                .andExpect(content().string(not(containsString("AIza"))));
    }

    @Test
    void unexpectedExceptionsAreAnonymousInternalErrors() throws Exception {
        problem(mockMvc.perform(traced(get("/test/boom"))), 500, "INTERNAL_ERROR", "internal-error")
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.INTERNAL_ERROR_DETAIL))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at com.techinterviewer"))));
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"application/xml", "text/html", "garbage", "text/*;;;"})
    void unacceptableAcceptHeadersStillGetAProblemDocument(String accept) throws Exception {
        problem(mockMvc.perform(traced(get("/test/json-only")).header("Accept", accept)),
                406, "NOT_ACCEPTABLE", "not-acceptable");
    }

    @Test
    void errorsAreProblemJsonEvenWhenTheAcceptHeaderCannotBeParsed() throws Exception {
        String garbage = "garbage";

        problem(mockMvc.perform(traced(get("/test/does-not-exist")).header("Accept", garbage)),
                404, "NOT_FOUND", "not-found");
        problem(mockMvc.perform(traced(get("/test/api-error")).header("Accept", garbage)),
                409, "CONFLICT", "conflict");
        problem(mockMvc.perform(traced(get("/test/boom")).header("Accept", garbage)),
                500, "INTERNAL_ERROR", "internal-error");
        problem(mockMvc.perform(traced(post("/test/body")).header("Accept", garbage)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": ")),
                400, "MALFORMED_JSON", "malformed-json");
        problem(mockMvc.perform(traced(get("/test/param")).header("Accept", garbage).param("count", "0")),
                400, "VALIDATION_FAILED", "validation-failed");
    }

    @ParameterizedTest(name = "status {0} maps to {1}")
    @CsvSource({
        "401, UNAUTHENTICATED, unauthenticated",
        "403, FORBIDDEN, forbidden",
        "409, CONFLICT, conflict",
        "410, BAD_REQUEST, bad-request",
        "502, INTERNAL_ERROR, internal-error",
        "503, INTERNAL_ERROR, internal-error"
    })
    void springMvcExceptionsAreMappedThroughTheirStatus(int status, String code, String slug) throws Exception {
        ResultActions result = problem(mockMvc.perform(traced(get("/test/status/" + status))), status, code, slug)
                .andExpect(content().string(not(containsString("cause secret"))));

        if (status >= 500) {
            result.andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.INTERNAL_ERROR_DETAIL));
        } else {
            result.andExpect(jsonPath("$.detail").value("internal detail for status " + status));
        }
    }

    @Test
    void statusesWithoutADedicatedCodeFallBackToTheGenericOnes() {
        assertThat(ErrorCode.forStatus(HttpStatus.UNAUTHORIZED)).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(ErrorCode.forStatus(HttpStatus.FORBIDDEN)).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ErrorCode.forStatus(HttpStatus.NOT_ACCEPTABLE)).isEqualTo(ErrorCode.NOT_ACCEPTABLE);
        assertThat(ErrorCode.forStatus(HttpStatus.CONTENT_TOO_LARGE)).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(ErrorCode.forStatus(HttpStatus.UNPROCESSABLE_CONTENT)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.forStatus(HttpStatus.BAD_GATEWAY)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.forStatus(HttpStatus.GATEWAY_TIMEOUT)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void securityExceptionsAreLeftToSpringSecurity() {
        assertThatThrownBy(() -> mockMvc.perform(get("/test/denied")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    void generatedTraceIdIsUsedWhenTheClientSendsNone() throws Exception {
        mockMvc.perform(get("/test/api-error"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void optimisticLockFailureIsAConcurrentModificationWithoutEntityDetails() throws Exception {
        problem(mockMvc.perform(traced(get("/test/optimistic-lock"))), 409, "CONCURRENT_MODIFICATION", "concurrent-modification")
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.CONCURRENT_MODIFICATION_DETAIL))
                .andExpect(content().string(not(containsString("Interview"))))
                .andExpect(content().string(not(containsString("secret-id"))))
                .andExpect(content().string(not(containsString("techinterviewer.interview"))));
    }

    @Test
    void anUnreachableDatabaseIsARetryableServiceUnavailableThatNeverShowsTheConnectionDetails() throws Exception {
        problem(mockMvc.perform(traced(get("/test/database-down"))), 503, "DATABASE_UNAVAILABLE", "database-unavailable")
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.DATABASE_UNAVAILABLE_DETAIL))
                .andExpect(content().string(not(containsString("jdbc:"))))
                .andExpect(content().string(not(containsString("internal-host"))));
    }

    @Test
    void dataIntegrityViolationIsAGenericConflictThatNeverShowsSqlOrConstraints() throws Exception {
        problem(mockMvc.perform(traced(get("/test/data-integrity"))), 409, "CONFLICT", "conflict")
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.DATA_INTEGRITY_DETAIL))
                .andExpect(content().string(not(containsString("uq_users_email"))))
                .andExpect(content().string(not(containsString("insert into"))))
                .andExpect(content().string(not(containsString("duplicate key"))))
                .andExpect(content().string(not(containsString("password_hash"))));
    }

    @Test
    void apiExceptionCanCarryExtraProblemMembers() throws Exception {
        problem(mockMvc.perform(traced(get("/test/state-conflict"))), 409, "QUESTION_NOT_CURRENT", "question-not-current")
                .andExpect(jsonPath("$.currentState").value("QUESTIONING"))
                .andExpect(jsonPath("$.detail").value("The interview has moved on; reload the session."));
    }

    @Test
    void apiExceptionCanCarryResponseHeaders() throws Exception {
        problem(mockMvc.perform(traced(get("/test/rate-limited"))), 429, "RATE_LIMITED", "rate-limited")
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("X-Test", "yes"));
    }

    @Test
    void retryAfterIsRoundedUpToWholeSecondsAndNeverBelowOne() {
        assertThat(new ApiException(ErrorCode.RATE_LIMITED, "x").withRetryAfter(java.time.Duration.ofMillis(1)).headers())
                .containsEntry("Retry-After", "1");
        assertThat(new ApiException(ErrorCode.RATE_LIMITED, "x").withRetryAfter(java.time.Duration.ZERO).headers())
                .containsEntry("Retry-After", "1");
        assertThat(new ApiException(ErrorCode.RATE_LIMITED, "x").withRetryAfter(java.time.Duration.ofMillis(1000)).headers())
                .containsEntry("Retry-After", "1");
        assertThat(new ApiException(ErrorCode.RATE_LIMITED, "x").withRetryAfter(java.time.Duration.ofMillis(1001)).headers())
                .containsEntry("Retry-After", "2");
        assertThat(new ApiException(ErrorCode.RATE_LIMITED, "x").withRetryAfter(java.time.Duration.ofSeconds(60)).headers())
                .containsEntry("Retry-After", "60");
    }

    @Test
    void anExtensionCannotReplaceAStandardProblemMember() throws Exception {
        assertThatThrownBy(() -> new ApiException(ErrorCode.CONFLICT, "x").withProperty("status", 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiException(ErrorCode.CONFLICT, "x").withProperty("traceId", "forged"))
                .isInstanceOf(IllegalArgumentException.class);
        // The endpoint that attempts it fails loudly instead of answering with a forged document.
        problem(mockMvc.perform(traced(get("/test/reserved-property"))), 500, "INTERNAL_ERROR", "internal-error");
    }

    @ParameterizedTest(name = "{0} answers {1}")
    @CsvSource({
        "EMAIL_TAKEN, 409", "INVALID_CREDENTIALS, 401", "TOKEN_EXPIRED, 401", "ILLEGAL_STATE_TRANSITION, 409",
        "QUESTION_NOT_CURRENT, 409", "CONCURRENT_MODIFICATION, 409", "INTERVIEW_EXPIRED, 409", "INTERVIEW_NOT_FINISHED, 409",
        "CONFIGURATION_IN_USE, 409", "RATE_LIMITED, 429", "DAILY_INTERVIEW_LIMIT, 429", "AI_PROVIDER_UNAVAILABLE, 503",
        "VALIDATION_FAILED, 400", "NOT_FOUND, 404", "FORBIDDEN, 403", "UNAUTHENTICATED, 401"
    })
    void everyErrorCodeAnswersWithItsDocumentedStatusAndTypeUrl(ErrorCode code, int status) throws Exception {
        String slug = code.name().toLowerCase().replace('_', '-');

        problem(mockMvc.perform(traced(get("/test/code/" + code.name()))), status, code.name(), slug);
        assertThat(code.status().value()).isEqualTo(status);
    }

    @Test
    void tooManyRequestsFromTheInfrastructureMapToTheRateLimitCode() {
        assertThat(ErrorCode.forStatus(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void pathVariableConstraintsOfAValidatedControllerAreFieldErrors() throws Exception {
        problem(mockMvc.perform(traced(get("/test/validated/items/0"))), 400, "VALIDATION_FAILED", "validation-failed")
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        problem(mockMvc.perform(traced(get("/test/validated/items/1001"))), 400, "VALIDATION_FAILED", "validation-failed")
                .andExpect(jsonPath("$.errors[0].field").value("id"));
    }

    @Test
    void queryConstraintsOfAValidatedControllerAreFieldErrorsAndAllViolationsAreListed() throws Exception {
        problem(mockMvc.perform(traced(get("/test/validated/items/5")).param("q", "x")), 400, "VALIDATION_FAILED", "validation-failed")
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        problem(mockMvc.perform(traced(get("/test/validated/items/0")).param("q", "toolong")), 400, "VALIDATION_FAILED", "validation-failed")
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[1].field").value("q"));
    }

    @Test
    void validValuesPassTheValidatedController() throws Exception {
        mockMvc.perform(traced(get("/test/validated/items/5")).param("q", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string("item 5 abc"));
    }

    @Test
    void everyErrorCodeHasAUniqueKebabCaseTypeUrl() {
        assertThat(ErrorCode.values())
                .extracting(code -> code.type().toString())
                .doesNotHaveDuplicates()
                .allMatch(type -> type.matches("https://techinterviewer\\.dev/problems/[a-z]+(-[a-z]+)*"));
    }
}
