package com.techinterviewer.interview.api;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.common.api.PageResponse;
import com.techinterviewer.interview.api.dto.CreateInterviewRequest;
import com.techinterviewer.interview.api.dto.InterviewResponse;
import com.techinterviewer.interview.api.dto.InterviewSummaryResponse;
import com.techinterviewer.interview.api.dto.QuestionResponse;
import com.techinterviewer.interview.api.dto.SessionResponse;
import com.techinterviewer.interview.api.dto.StateResponse;
import com.techinterviewer.interview.api.dto.TranscriptResponse;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.service.InterviewCreationService;
import com.techinterviewer.interview.service.InterviewLifecycleService;
import com.techinterviewer.interview.service.InterviewQueryService;
import com.techinterviewer.security.AuthenticatedUser;
import com.techinterviewer.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's interviews: create, list, look at, start and abandon. The user comes from the access token; an interview
 * (or a question) of somebody else answers 404 like a missing one. Every read applies the lazy deadline.
 */
@RestController
@RequestMapping(ApiPaths.BASE + "/interviews")
@Tag(name = "Interviews")
public class InterviewController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "application/problem+json";
    private static final String PROBLEM_REF = "#/components/schemas/Problem";
    private static final String BAD_ID = "An id is not a UUID";
    private static final String NOT_FOUND = "No such interview of the caller (code NOT_FOUND)";

    private final InterviewCreationService creation;
    private final InterviewLifecycleService lifecycle;
    private final InterviewQueryService queries;

    public InterviewController(InterviewCreationService creation, InterviewLifecycleService lifecycle, InterviewQueryService queries) {
        this.creation = creation;
        this.lifecycle = lifecycle;
        this.queries = queries;
    }

    @PostMapping(consumes = JSON, produces = JSON)
    @Operation(
            operationId = "createInterview",
            summary = "Create an interview",
            description = "Creates an interview in state INIT from a saved configuration (configurationId) or from an inline "
                    + "configuration that is saved for the caller. Exactly one of the two must be sent. The response carries the "
                    + "configuration and the public plan; the interview starts with POST /interviews/{id}/start. "
                    + "A caller may create a limited number of interviews per UTC day.")
    @ApiResponse(responseCode = "201", description = "The new interview",
            headers = @Header(name = "Location", description = "URL of the new interview"),
            content = @Content(mediaType = JSON, schema = @Schema(implementation = InterviewResponse.class)))
    @ApiResponse(responseCode = "400", description = "Both or neither source was sent, or the inline configuration is invalid (code VALIDATION_FAILED, field errors in errors)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = "The configuration does not exist or is not the caller's (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "429", description = "The caller's interviews of the day are used up (code DAILY_INTERVIEW_LIMIT); Retry-After says when the next UTC day starts",
            headers = @Header(name = "Retry-After", description = "Seconds until midnight UTC"),
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public ResponseEntity<InterviewResponse> create(@CurrentUser AuthenticatedUser user, @Valid @RequestBody CreateInterviewRequest request) {
        InterviewResponse created = creation.create(user.id(), request);
        return ResponseEntity.created(URI.create(ApiPaths.BASE + "/interviews/" + created.id())).body(created);
    }

    @GetMapping(produces = JSON)
    @Operation(
            operationId = "listInterviews",
            summary = "My interviews",
            description = "The caller's interviews, newest first, optionally only those in one state.")
    @ApiResponse(responseCode = "200", description = "One page of interviews")
    @ApiResponse(responseCode = "400", description = "page, size or state is invalid",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public PageResponse<InterviewSummaryResponse> list(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "Only interviews in this state") @RequestParam(required = false) @Nullable InterviewState state,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @Parameter(description = "Page size, 1 to 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.list(user.id(), state, page, size);
    }

    @GetMapping(path = "/{id}", produces = JSON)
    @Operation(
            operationId = "getInterview",
            summary = "Session view",
            description = "State, progress, deadline and the current question: everything needed to show or resume an interview. "
                    + "If the deadline passed, the interview is moved to FINAL_EVALUATION first.")
    @ApiResponse(responseCode = "200", description = "The session",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "Another request changed the interview while its deadline was applied (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public SessionResponse session(@CurrentUser AuthenticatedUser user, @Parameter(description = "Interview id") @PathVariable UUID id) {
        return queries.session(user.id(), id);
    }

    @GetMapping(path = "/{id}/state", produces = JSON)
    @Operation(
            operationId = "getInterviewState",
            summary = "Interview state",
            description = "The lightweight view for polling: state, progress, deadline, server time, version and whether the next question is awaited.")
    @ApiResponse(responseCode = "200", description = "The state",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = StateResponse.class)))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "Another request changed the interview while its deadline was applied (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public StateResponse state(@CurrentUser AuthenticatedUser user, @Parameter(description = "Interview id") @PathVariable UUID id) {
        return queries.state(user.id(), id);
    }

    @PostMapping(path = "/{id}/start", produces = JSON)
    @Operation(
            operationId = "startInterview",
            summary = "Start an interview",
            description = "INIT to INTRODUCTION: starts the clock, asks the introduction question and returns the session. "
                    + "Of two simultaneous starts exactly one wins.")
    @ApiResponse(responseCode = "200", description = "The started session, with the introduction as its current question",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "The interview is not in INIT (code ILLEGAL_STATE_TRANSITION, with currentState) or another request changed it first (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public SessionResponse start(@CurrentUser AuthenticatedUser user, @Parameter(description = "Interview id") @PathVariable UUID id) {
        return lifecycle.start(user.id(), id);
    }

    @GetMapping(path = "/{id}/questions", produces = JSON)
    @Operation(
            operationId = "listInterviewQuestions",
            summary = "Questions asked so far",
            description = "The questions of the interview in the order they were asked, as the candidate sees them, each with an answered flag.")
    @ApiResponse(responseCode = "200", description = "The questions",
            content = @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = QuestionResponse.class))))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "Another request changed the interview while its deadline was applied (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public List<QuestionResponse> questions(@CurrentUser AuthenticatedUser user, @Parameter(description = "Interview id") @PathVariable UUID id) {
        return queries.questions(user.id(), id);
    }

    @GetMapping(path = "/{id}/questions/current", produces = JSON)
    @Operation(
            operationId = "getCurrentQuestion",
            summary = "The current question",
            description = "The question the candidate is to answer now.")
    @ApiResponse(responseCode = "200", description = "The current question",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = QuestionResponse.class)))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = "No such interview of the caller, or the interview has no open question (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "Another request changed the interview while its deadline was applied (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public QuestionResponse currentQuestion(@CurrentUser AuthenticatedUser user, @Parameter(description = "Interview id") @PathVariable UUID id) {
        return queries.currentQuestion(user.id(), id);
    }

    @GetMapping(path = "/{id}/questions/{questionId}", produces = JSON)
    @Operation(
            operationId = "getInterviewQuestion",
            summary = "One question",
            description = "A question that was asked in the interview, as the candidate sees it.")
    @ApiResponse(responseCode = "200", description = "The question",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = QuestionResponse.class)))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = "No such interview of the caller, or the question is not one of its questions (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "Another request changed the interview while its deadline was applied (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public QuestionResponse question(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "Interview id") @PathVariable UUID id,
            @Parameter(description = "Question id") @PathVariable UUID questionId) {
        return queries.question(user.id(), id, questionId);
    }

    @PostMapping(path = "/{id}/abandon", produces = JSON)
    @Operation(
            operationId = "abandonInterview",
            summary = "Abandon an interview",
            description = "INIT to ABANDONED: cancels an interview that was not started. A started interview cannot be abandoned.")
    @ApiResponse(responseCode = "200", description = "The abandoned session",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "The interview is not in INIT (code ILLEGAL_STATE_TRANSITION, with currentState) or another request changed it first (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public SessionResponse abandon(@CurrentUser AuthenticatedUser user, @Parameter(description = "Interview id") @PathVariable UUID id) {
        return lifecycle.abandon(user.id(), id);
    }

    @GetMapping(path = "/{id}/transcript", produces = JSON)
    @Operation(
            operationId = "getInterviewTranscript",
            summary = "Transcript",
            description = "The questions with the answers given. Feedback (score, criteria, key points) is included only once the interview is COMPLETED.")
    @ApiResponse(responseCode = "200", description = "The transcript",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = TranscriptResponse.class)))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "Another request changed the interview while its deadline was applied (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public TranscriptResponse transcript(@CurrentUser AuthenticatedUser user, @Parameter(description = "Interview id") @PathVariable UUID id) {
        return queries.transcript(user.id(), id);
    }
}
