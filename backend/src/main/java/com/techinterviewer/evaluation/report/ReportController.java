package com.techinterviewer.evaluation.report;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.report.dto.ReportResponse;
import com.techinterviewer.security.AuthenticatedUser;
import com.techinterviewer.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The report of an interview. The user comes from the access token; an interview of somebody else is a 404. The body
 * never contains the questions' expected key points or rubrics, and there is nothing a client can send to change a
 * score.
 */
@RestController
@RequestMapping(ApiPaths.BASE + "/interviews/{interviewId}/report")
@Tag(name = "Report")
public class ReportController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "application/problem+json";

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping(produces = JSON)
    @Operation(
            operationId = "getInterviewReport",
            summary = "The report of an interview",
            description = "Available once the interview is in FINAL_EVALUATION or COMPLETED. A report that is not ready "
                    + "(PENDING, GENERATING) answers 202; a READY or FAILED report answers 200. A FAILED report carries "
                    + "errorMessage and retryable: POST /report/retry makes another attempt.")
    @ApiResponse(responseCode = "200", description = "A READY or FAILED report",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ReportResponse.class)))
    @ApiResponse(responseCode = "202", description = "The report is not ready yet (status PENDING or GENERATING); ask again shortly",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ReportResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such interview, or it belongs to somebody else (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(responseCode = "409", description = "The interview has not finished (code INTERVIEW_NOT_FINISHED)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    public ResponseEntity<ReportResponse> get(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "The interview") @PathVariable UUID interviewId) {
        return respond(reports.get(user.id(), interviewId));
    }

    @PostMapping(path = "/retry", produces = JSON)
    @Operation(
            operationId = "retryInterviewReport",
            summary = "Retry the report of an interview",
            description = "Makes another attempt at a report that failed (answers still awaiting evaluation, or the AI service "
                    + "was unavailable) and returns the report as GET does. Idempotent: repeating it while an attempt is "
                    + "running returns that attempt's result. Only an interview in FINAL_EVALUATION can be retried.")
    @ApiResponse(responseCode = "200", description = "The report after the attempt: READY, or FAILED again",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ReportResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such interview, or it belongs to somebody else (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(responseCode = "409", description = "The interview is not in FINAL_EVALUATION (code ILLEGAL_STATE_TRANSITION, currentState)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    public ResponseEntity<ReportResponse> retry(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "The interview") @PathVariable UUID interviewId) {
        return respond(reports.retry(user.id(), interviewId));
    }

    /** A report that is still being made answers 202, every other one 200. */
    private static ResponseEntity<ReportResponse> respond(ReportResponse report) {
        boolean inProgress = report.status() == ReportStatus.PENDING || report.status() == ReportStatus.GENERATING;
        return ResponseEntity.status(inProgress ? HttpStatus.ACCEPTED : HttpStatus.OK).body(report);
    }
}
