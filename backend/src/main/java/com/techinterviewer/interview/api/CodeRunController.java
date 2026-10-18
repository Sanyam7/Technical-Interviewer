package com.techinterviewer.interview.api;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.interview.api.dto.CodeRunRequest;
import com.techinterviewer.interview.api.dto.CodeRunResponse;
import com.techinterviewer.interview.service.CodeRunService;
import com.techinterviewer.security.AuthenticatedUser;
import com.techinterviewer.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Run examples" for the open coding question: the candidate checks a draft against the visible examples before submitting. */
@RestController
@RequestMapping(ApiPaths.BASE + "/interviews")
@Tag(name = "Interview turns")
public class CodeRunController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "application/problem+json";
    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    private final CodeRunService runs;

    public CodeRunController(CodeRunService runs) {
        this.runs = runs;
    }

    @PostMapping(path = "/{id}/questions/{questionId}/runs", consumes = JSON, produces = JSON)
    @Operation(
            operationId = "runCodeExamples",
            summary = "Run a draft against the visible examples",
            description = "Runs the draft solution of the open coding question in an isolated sandbox against the examples shown with "
                    + "the problem, and returns each example's result (input, expected output, what the code returned). Nothing is "
                    + "stored or evaluated: the submitted answer is run again on every test, hidden ones included, before its "
                    + "evaluation. Only questions taken from the problem bank have runnable examples. At most 10 runs per minute per user.")
    @ApiResponse(responseCode = "200", description = "The examples ran (or the code did not compile: see status)",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = CodeRunResponse.class)))
    @ApiResponse(responseCode = "400", description = "Malformed body, or a language that is not the interview's (code VALIDATION_FAILED)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = "No such interview or question of the caller (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "Not the open question (QUESTION_NOT_CURRENT), the interview takes no answers "
            + "(ILLEGAL_STATE_TRANSITION), or the question has no runnable examples (CONFLICT)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "429", description = "Too many runs (code RATE_LIMITED)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer")),
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "503", description = "Code execution is off, not offered for the language, or busy (code "
            + "CODE_EXECUTION_UNAVAILABLE; Retry-After when busy)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public CodeRunResponse run(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "Interview id") @PathVariable UUID id,
            @Parameter(description = "Id of the open coding question") @PathVariable UUID questionId,
            @Valid @RequestBody CodeRunRequest request) {
        return runs.runExamples(user.id(), id, questionId, request);
    }
}
