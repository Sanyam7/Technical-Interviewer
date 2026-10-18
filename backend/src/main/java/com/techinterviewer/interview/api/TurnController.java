package com.techinterviewer.interview.api;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.interview.api.dto.AnswerRequest;
import com.techinterviewer.interview.api.dto.AnswerResponse;
import com.techinterviewer.interview.api.dto.FinishRequest;
import com.techinterviewer.interview.api.dto.FinishResponse;
import com.techinterviewer.interview.api.dto.SessionResponse;
import com.techinterviewer.interview.api.dto.SkipRequest;
import com.techinterviewer.interview.service.FinishService;
import com.techinterviewer.interview.service.NextQuestionService;
import com.techinterviewer.interview.service.TurnService;
import com.techinterviewer.security.AuthenticatedUser;
import com.techinterviewer.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The turn of an interview: answer or skip the current question, recover a next question that could not be generated,
 * and finish. The server decides everything that follows (state, next question, scores): a request carries only what the
 * candidate said. The user comes from the access token; an interview of somebody else answers 404 like a missing one.
 */
@RestController
@RequestMapping(ApiPaths.BASE + "/interviews")
@Tag(name = "Interview turns")
public class TurnController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "application/problem+json";
    private static final String PROBLEM_REF = "#/components/schemas/Problem";
    private static final String BAD_ID = "An id is not a UUID, or the body is malformed or breaks a rule (code BAD_REQUEST, MALFORMED_JSON or VALIDATION_FAILED)";
    private static final String NOT_FOUND = "No such interview of the caller (code NOT_FOUND)";

    private final TurnService turns;
    private final NextQuestionService nextQuestions;
    private final FinishService finishes;

    public TurnController(TurnService turns, NextQuestionService nextQuestions, FinishService finishes) {
        this.turns = turns;
        this.nextQuestions = nextQuestions;
        this.finishes = finishes;
    }

    @PostMapping(path = "/{id}/answers", consumes = JSON, produces = JSON)
    @Operation(
            operationId = "submitAnswer",
            summary = "Answer the current question",
            description = "Stores the answer, has it evaluated, lets the server decide what follows and returns the next question. "
                    + "The answer is accepted even when the evaluation or the next question is unavailable: the evaluation is made later "
                    + "and awaitingNextQuestion tells the client to call POST /questions/next. Posting an answer for a question that is "
                    + "answered already is an idempotent retry: it answers 200 with the current state and nothing is evaluated twice. "
                    + "Nothing about how the answer was judged is ever returned. A client cannot send a score, a state or a sequence "
                    + "number: unknown properties are refused. At most 20 answers per minute per user.")
    @ApiResponse(responseCode = "200", description = "The interview after the turn",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = AnswerResponse.class), examples = @ExampleObject(
                    name = "A follow-up is next",
                    value = "{\"accepted\": true, \"state\": \"FOLLOW_UP\", \"progress\": {\"section\": \"QUESTIONING\", \"sectionIndex\": 1, "
                            + "\"sectionCount\": 2, \"questionsAnswered\": 3, \"plannedQuestionsInSection\": 8, \"remainingSeconds\": 2030}, "
                            + "\"nextQuestion\": {\"id\": \"0192f1e4-0000-7000-8000-000000000001\", \"sequenceNo\": 5, \"kind\": \"FOLLOW_UP\", "
                            + "\"isFollowUp\": true, \"section\": \"QUESTIONING\", \"topic\": {\"skill\": \"Spring Boot\", \"name\": \"Transactions\"}, "
                            + "\"prompt\": \"How would you make the inner method start a new transaction?\", \"answerFormat\": \"TEXT\", "
                            + "\"suggestedMinutes\": 2, \"answered\": false}, \"awaitingNextQuestion\": false, \"interviewComplete\": false}")))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF),
            examples = @ExampleObject(name = "Unknown property", value = "{\"type\": \"https://techinterviewer.dev/problems/malformed-json\", "
                    + "\"title\": \"Malformed request body\", \"status\": 400, \"code\": \"MALFORMED_JSON\"}")))
    @ApiResponse(responseCode = "404", description = "No such interview of the caller, or the question is not part of the interview (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "ILLEGAL_STATE_TRANSITION (the interview asks no questions), INTERVIEW_EXPIRED (the time is up; the interview is now in "
            + "FINAL_EVALUATION), QUESTION_NOT_CURRENT (the interview has moved on) or CONCURRENT_MODIFICATION; each carries currentState",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF), examples = @ExampleObject(
                    name = "Not the current question", value = "{\"type\": \"https://techinterviewer.dev/problems/question-not-current\", "
                            + "\"title\": \"Question is not the current question\", \"status\": 409, \"code\": \"QUESTION_NOT_CURRENT\", "
                            + "\"currentState\": \"QUESTIONING\"}")))
    @ApiResponse(responseCode = "429", description = "More than 20 answers in a minute (code RATE_LIMITED); Retry-After says when to try again",
            headers = @Header(name = "Retry-After", description = "Seconds until the next answer is allowed"),
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public AnswerResponse answer(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "Interview id") @PathVariable UUID id,
            @Valid @RequestBody AnswerRequest request) {
        return turns.answer(user.id(), id, request);
    }

    @PostMapping(path = "/{id}/questions/{questionId}/skip", consumes = JSON, produces = JSON)
    @Operation(
            operationId = "skipQuestion",
            summary = "Skip the current question",
            description = "Records the question as skipped and moves on exactly like an answer: a skipped main question scores 0, a skipped "
                    + "follow-up adds nothing. Skipping an answered question is an idempotent retry. The body is optional.")
    @ApiResponse(responseCode = "200", description = "The interview after the turn",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = AnswerResponse.class)))
    @ApiResponse(responseCode = "400", description = BAD_ID, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = "No such interview of the caller, or the question is not part of the interview (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "ILLEGAL_STATE_TRANSITION, INTERVIEW_EXPIRED, QUESTION_NOT_CURRENT or CONCURRENT_MODIFICATION; each carries currentState",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public AnswerResponse skip(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "Interview id") @PathVariable UUID id,
            @Parameter(description = "Id of the question to skip") @PathVariable UUID questionId,
            @Valid @RequestBody(required = false) @Nullable SkipRequest request) {
        return turns.skip(user.id(), id, questionId, request == null ? null : request.timeTakenSeconds());
    }

    @PostMapping(path = "/{id}/questions/next", produces = JSON)
    @Operation(
            operationId = "nextQuestion",
            summary = "Get the next question",
            description = "Recovery for an interview whose next question could not be generated when the last answer was accepted "
                    + "(awaitingNextQuestion is true). It generates the question, stores it and returns the session. Idempotent: when a "
                    + "question is open already the session is returned unchanged, so calling it twice never creates two questions.")
    @ApiResponse(responseCode = "200", description = "The session, with the next question as its current question",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "400", description = "An id is not a UUID", content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "The interview asks no questions (code ILLEGAL_STATE_TRANSITION or INTERVIEW_EXPIRED, with currentState), "
            + "or a concurrent change prevented it (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "503", description = "The AI service cannot produce the question right now (code AI_PROVIDER_UNAVAILABLE); nothing changed, try again",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public SessionResponse next(@CurrentUser AuthenticatedUser user, @Parameter(description = "Interview id") @PathVariable UUID id) {
        return nextQuestions.next(user.id(), id);
    }

    @PostMapping(path = "/{id}/finish", consumes = JSON, produces = JSON)
    @Operation(
            operationId = "finishInterview",
            summary = "Finish the interview and get the report",
            description = "CANDIDATE_ENDED ends a running interview early; ACKNOWLEDGE_COMPLETE is valid once the question phase is over "
                    + "(FINAL_EVALUATION) or the interview is COMPLETED. Either way the final evaluation runs inside this request: answers that "
                    + "are still pending are evaluated, the report is computed and the interview becomes COMPLETED. When the report cannot be "
                    + "completed (the AI service is unavailable) the call still answers 200 with state FINAL_EVALUATION, reportStatus FAILED and "
                    + "retryable true: nothing is lost, repeat the call or use POST /report/retry. Idempotent.")
    @ApiResponse(responseCode = "200", description = "COMPLETED with a READY report, or FINAL_EVALUATION with a FAILED, retryable one",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = FinishResponse.class), examples = {
                    @ExampleObject(name = "Ready", value = "{\"state\": \"COMPLETED\", \"reportStatus\": \"READY\", \"retryable\": false, "
                            + "\"reportUrl\": \"/api/v1/interviews/0192f1c2-0000-7000-8000-000000000001/report\"}"),
                    @ExampleObject(name = "Failed, retryable", value = "{\"state\": \"FINAL_EVALUATION\", \"reportStatus\": \"FAILED\", \"retryable\": true}")}))
    @ApiResponse(responseCode = "400", description = "An id is not a UUID, or the body is malformed (code BAD_REQUEST, MALFORMED_JSON or VALIDATION_FAILED)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    @ApiResponse(responseCode = "409", description = "The interview was never started or was abandoned, or ACKNOWLEDGE_COMPLETE was sent while it still asks "
            + "questions (code ILLEGAL_STATE_TRANSITION, with currentState), or a concurrent change (code CONCURRENT_MODIFICATION)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = PROBLEM_REF)))
    public FinishResponse finish(
            @CurrentUser AuthenticatedUser user,
            @Parameter(description = "Interview id") @PathVariable UUID id,
            @Valid @RequestBody FinishRequest request) {
        return finishes.finish(user.id(), id, request.reason());
    }
}
