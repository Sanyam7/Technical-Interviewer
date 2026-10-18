package com.techinterviewer.common.error;

import com.techinterviewer.common.logging.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the servlet container's error dispatch (anything that calls {@code sendError}, such as
 * Spring Security's firewall rejections) with the same problem document as the rest of the API.
 * It replaces Spring Boot's {@code BasicErrorController}, whose JSON or HTML shape depends on the
 * {@code Accept} header and carries neither {@code code} nor {@code traceId}.
 *
 * <p>The content type is set explicitly, so content negotiation (and an unparseable {@code Accept}
 * header) cannot make the response unwritable. The detail is generic: the container's error message
 * and exception are never returned. The correlation filter has already left the logging MDC when the
 * container dispatches the error, so the trace id is read back from the {@code X-Request-Id} response header.
 */
@Hidden
@RestController
@RequestMapping("${spring.web.error.path:${error.path:/error}}")
public class ProblemErrorController implements ErrorController {

    static final String SERVER_ERROR_DETAIL = GlobalExceptionHandler.INTERNAL_ERROR_DETAIL;
    static final String CLIENT_ERROR_DETAIL = "The request could not be processed.";
    static final String NOT_FOUND_DETAIL = "The requested resource was not found.";

    @RequestMapping
    ResponseEntity<ProblemDetail> error(HttpServletRequest request, HttpServletResponse response) {
        HttpStatusCode status = status(request);
        ErrorCode code = ErrorCode.forStatus(status);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail(status));
        ProblemDetails.populate(problem, code, List.of(), response.getHeader(CorrelationIdFilter.HEADER));
        problem.setInstance(instance(request));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static HttpStatusCode status(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer code) {
            HttpStatus resolved = HttpStatus.resolve(code);
            if (resolved != null && (resolved.is4xxClientError() || resolved.is5xxServerError())) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String detail(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return SERVER_ERROR_DETAIL;
        }
        return status.value() == HttpStatus.NOT_FOUND.value() ? NOT_FOUND_DETAIL : CLIENT_ERROR_DETAIL;
    }

    /** The path the client asked for, or none when the container reports something that is not a valid URI reference. */
    private static @Nullable URI instance(HttpServletRequest request) {
        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (path instanceof String value) {
            try {
                return new URI(value);
            } catch (URISyntaxException ex) {
                return null;
            }
        }
        return null;
    }
}
