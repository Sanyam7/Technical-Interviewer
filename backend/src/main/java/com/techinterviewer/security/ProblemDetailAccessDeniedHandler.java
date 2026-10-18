package com.techinterviewer.security;

import com.techinterviewer.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Answers authenticated requests that lack permission with a 403 problem document. */
@Component
class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter writer;

    ProblemDetailAccessDeniedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        writer.write(request, response, ErrorCode.FORBIDDEN, "You do not have permission to access this resource.");
    }
}
