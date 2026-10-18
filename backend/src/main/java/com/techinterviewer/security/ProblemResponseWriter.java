package com.techinterviewer.security;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes problem documents from servlet filters, where {@code @RestControllerAdvice} does not apply. */
@Component
class ProblemResponseWriter {

    private final JsonMapper jsonMapper;

    ProblemResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        write(request, response, ProblemDetails.of(code, detail), code);
    }

    /** Writes an {@link ApiException} exactly as the controller advice would, including its extra members and headers. */
    void write(HttpServletRequest request, HttpServletResponse response, ApiException exception) throws IOException {
        ErrorCode code = exception.errorCode();
        ProblemDetail problem = ProblemDetails.of(code, exception.getMessage());
        exception.properties().forEach(problem::setProperty);
        exception.headers().forEach(response::setHeader);
        write(request, response, problem, code);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem, ErrorCode code)
            throws IOException {
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
