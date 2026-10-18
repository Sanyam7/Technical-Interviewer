package com.techinterviewer.common.error;

import com.techinterviewer.common.logging.CorrelationIdFilter;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem documents in the one shape every error response of this API uses. */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        return of(code, detail, List.of());
    }

    public static ProblemDetail of(ErrorCode code, String detail, List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        populate(problem, code, errors);
        return problem;
    }

    /** Applies type, title, code, traceId and field errors to a problem whose status is already set. */
    static void populate(ProblemDetail problem, ErrorCode code, List<FieldViolation> errors) {
        populate(problem, code, errors, MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    /** Same, for code that runs after the correlation filter finished and so cannot read the logging MDC. */
    static void populate(ProblemDetail problem, ErrorCode code, List<FieldViolation> errors, @Nullable String traceId) {
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("code", code.code());
        problem.setProperty("traceId", traceId);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
    }
}
