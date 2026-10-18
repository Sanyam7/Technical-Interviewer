package com.techinterviewer.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every failure of a controller into an RFC 9457 problem document (see {@link ProblemDetails}).
 * Spring MVC's own exceptions are handled by {@link ResponseEntityExceptionHandler}; this class
 * decorates them with the API's {@code type}, {@code code} and {@code traceId} and adds our own cases.
 * Nothing about an unexpected exception is ever returned to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred. Quote the traceId when reporting it.";
    static final String MALFORMED_JSON_DETAIL = "The request body is missing or is not valid JSON for this endpoint.";
    static final String VALIDATION_DETAIL = "One or more fields are invalid.";
    static final String CONCURRENT_MODIFICATION_DETAIL =
            "The resource was modified by another request. Reload it and try again.";
    static final String DATABASE_UNAVAILABLE_DETAIL = "The service is temporarily unable to reach its database. Retry shortly.";
    static final long DATABASE_RETRY_AFTER_SECONDS = 5;
    static final String DATA_INTEGRITY_DETAIL = "The request conflicts with the current state of the resource.";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.warn("{} ({}): {}", code.code(), code.status().value(), ex.getMessage(), ex.getCause());
        }
        List<FieldViolation> errors = ex instanceof FieldValidationException invalid ? invalid.violations() : List.of();
        ProblemDetail problem = ProblemDetails.of(code, ex.getMessage(), errors);
        ex.properties().forEach(problem::setProperty);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        ex.headers().forEach(response::header);
        return response.body(problem);
    }

    /**
     * Another request changed the same row first (the {@code @Version} check of the interview aggregate failed).
     * Nothing about the entity is returned; the client reloads and retries.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.info("Optimistic lock conflict on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problemJson(
                ErrorCode.CONCURRENT_MODIFICATION.status(),
                ProblemDetails.of(ErrorCode.CONCURRENT_MODIFICATION, CONCURRENT_MODIFICATION_DETAIL));
    }

    /**
     * A database constraint rejected the write (a unique key raced, a row is still referenced). The SQL, the
     * constraint name and the offending values are logged, never returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problemJson(ErrorCode.CONFLICT.status(), ProblemDetails.of(ErrorCode.CONFLICT, DATA_INTEGRITY_DETAIL));
    }

    /**
     * The database cannot be reached (down, restarting, pool exhausted). It is a transient condition, not a bug: the client
     * is told to retry, and the log gets one line without the stack.
     */
    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        CannotCreateTransactionException.class,
        CannotGetJdbcConnectionException.class,
        TransientDataAccessResourceException.class
    })
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex, HttpServletRequest request) {
        log.warn("Database unavailable on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.toString());
        return ResponseEntity.status(ErrorCode.DATABASE_UNAVAILABLE.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Retry-After", Long.toString(DATABASE_RETRY_AFTER_SECONDS))
                .body(ProblemDetails.of(ErrorCode.DATABASE_UNAVAILABLE, DATABASE_UNAVAILABLE_DETAIL));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> errors = sorted(ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(parameterPath(violation), violation.getMessage()))
                .toList());
        return problemJson(
                ErrorCode.VALIDATION_FAILED.status(),
                ProblemDetails.of(ErrorCode.VALIDATION_FAILED, VALIDATION_DETAIL, errors));
    }

    /** Authorization failures must reach Spring Security's filters, which know how to answer 401 vs 403. */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void rethrowSecurityException(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problemJson(
                ErrorCode.INTERNAL_ERROR.status(), ProblemDetails.of(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (statusCode.is5xxServerError()) {
                log.error("Spring MVC failed with {}", statusCode, ex);
                problem.setDetail(INTERNAL_ERROR_DETAIL);
            }
            ProblemDetails.populate(problem, ErrorCode.forStatus(statusCode), List.of());
        }
        return asProblemJson(response);
    }

    /**
     * The media type is set explicitly because a problem must be writable whatever the client sent in
     * {@code Accept}: content negotiation fails for an unparseable header (for example {@code garbage}),
     * and the response would then be empty.
     */
    private static ResponseEntity<ProblemDetail> problemJson(HttpStatusCode status, ProblemDetail problem) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static @Nullable ResponseEntity<Object> asProblemJson(@Nullable ResponseEntity<Object> response) {
        if (response == null || !(response.getBody() instanceof ProblemDetail)) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(response.getBody());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> errors.add(violation(error)));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(error -> errors.add(new FieldViolation(error.getObjectName(), error.getDefaultMessage())));
        return validationFailed(ex, headers, status, request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(error -> errors.add(violation(error)));
            } else {
                String name = result.getMethodParameter().getParameterName();
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(new FieldViolation(name == null ? "" : name, error.getDefaultMessage()));
                }
            }
        }
        return validationFailed(ex, headers, status, request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (causedBy(ex, RequestBodyTooLargeException.class)) {
            return problemResponse(ex, headers, HttpStatus.CONTENT_TOO_LARGE, request, ErrorCode.PAYLOAD_TOO_LARGE,
                    "The request body is too large.", List.of());
        }
        List<FieldViolation> errors = List.of();
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String message = mismatch instanceof UnrecognizedPropertyException ? "Unknown property" : "Invalid value or type";
            errors = List.of(new FieldViolation(jsonPath(mismatch), message));
        }
        return problemResponse(ex, headers, status, request, ErrorCode.MALFORMED_JSON, MALFORMED_JSON_DETAIL, errors);
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<Object> validationFailed(
            Exception ex, HttpHeaders headers, HttpStatusCode status, WebRequest request, List<FieldViolation> errors) {
        return problemResponse(ex, headers, status, request, ErrorCode.VALIDATION_FAILED, VALIDATION_DETAIL, sorted(errors));
    }

    private ResponseEntity<Object> problemResponse(
            Exception ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request,
            ErrorCode code,
            String detail,
            List<FieldViolation> errors) {
        ProblemDetail problem = createProblemDetail(ex, status, detail, null, null, request);
        problem.setDetail(detail);
        ProblemDetails.populate(problem, code, errors);
        return asProblemJson(super.handleExceptionInternal(ex, problem, headers, status, request));
    }

    private static FieldViolation violation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private static List<FieldViolation> sorted(List<FieldViolation> errors) {
        return errors.stream()
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
    }

    /** Property path without the leading method name, e.g. {@code create.request.name} becomes {@code request.name}. */
    private static String parameterPath(ConstraintViolation<?> violation) {
        List<String> names = new ArrayList<>();
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getName() != null) {
                names.add(node.getName());
            }
        }
        return String.join(".", names.size() > 1 ? names.subList(1, names.size()) : names);
    }

    private static String jsonPath(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : ex.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
