package com.techinterviewer.common.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that raise every kind of failure. The profile keeps component scanning in
 * Spring Boot tests from ever registering it; only {@link GlobalExceptionHandlerTest} activates it.
 */
@RestController
@Profile(ErrorTestController.PROFILE)
@RequestMapping("/test")
class ErrorTestController {

    static final String PROFILE = "error-handler-test";

    record Candidate(@NotBlank String name, @Min(18) int age, @Size(max = 2) List<String> tags) {
    }

    @PostMapping("/body")
    Candidate body(@Valid @RequestBody Candidate candidate) {
        return candidate;
    }

    @GetMapping("/param")
    String param(@RequestParam @Min(1) @Max(100) int count) {
        return "ok " + count;
    }

    @GetMapping("/constraint")
    String constraint() throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("validatedCall", String.class);
        Set<ConstraintViolation<ErrorTestController>> violations = Validation.buildDefaultValidatorFactory()
                .getValidator()
                .forExecutables()
                .validateParameters(this, method, new Object[] {""});
        throw new ConstraintViolationException(violations);
    }

    String validatedCall(@NotBlank String title) {
        return title;
    }

    @GetMapping("/api-error")
    String apiError() {
        throw new ApiException(ErrorCode.CONFLICT, "The interview was modified in another tab.");
    }

    @GetMapping("/provider-down")
    String providerDown() {
        throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "The AI provider is temporarily unavailable.",
                new IllegalStateException("gemini returned 503 for key AIza-secret"));
    }

    @GetMapping("/boom")
    String boom() {
        throw new IllegalStateException("jdbc:postgresql://internal-host/db password=hunter2");
    }

    @GetMapping("/denied")
    String denied() {
        throw new AccessDeniedException("not allowed");
    }

    @GetMapping(path = "/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
    String jsonOnly() {
        return "{}";
    }

    /** Raises the Spring MVC exception that carries the given status, with a detail and cause that must not leak. */
    @GetMapping("/status/{status}")
    String status(@PathVariable int status) {
        HttpStatus httpStatus = HttpStatus.valueOf(status);
        throw new ErrorResponseException(
                httpStatus,
                ProblemDetail.forStatusAndDetail(httpStatus, "internal detail for status " + status),
                new IllegalStateException("cause secret"));
    }

    @GetMapping("/database-down")
    String databaseDown() {
        throw new org.springframework.transaction.CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction: jdbc:postgresql://internal-host:5432/db user=app");
    }

    @GetMapping("/optimistic-lock")
    String optimisticLock() {
        throw new ObjectOptimisticLockingFailureException("com.techinterviewer.interview.domain.Interview", "0192f1c2-secret-id");
    }

    @GetMapping("/data-integrity")
    String dataIntegrity() {
        throw new DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint \"uq_users_email\"] "
                        + "[insert into users (email, password_hash) values (?, ?)]");
    }

    @GetMapping("/state-conflict")
    String stateConflict() {
        throw new ApiException(ErrorCode.QUESTION_NOT_CURRENT, "The interview has moved on; reload the session.")
                .withProperty("currentState", "QUESTIONING");
    }

    @GetMapping("/rate-limited")
    String rateLimited() {
        throw new ApiException(ErrorCode.RATE_LIMITED, "Too many requests.")
                .withRetryAfter(java.time.Duration.ofMillis(1500))
                .withHeader("X-Test", "yes");
    }

    @GetMapping("/reserved-property")
    String reservedProperty() {
        throw new ApiException(ErrorCode.CONFLICT, "x").withProperty("status", 200);
    }

    @GetMapping("/code/{code}")
    String code(@PathVariable ErrorCode code) {
        throw new ApiException(code, "raised for the test");
    }

    @GetMapping("/typed")
    String typed(@RequestParam int number) {
        return "ok " + number;
    }
}
