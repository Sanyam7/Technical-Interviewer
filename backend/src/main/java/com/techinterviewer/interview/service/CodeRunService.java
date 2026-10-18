package com.techinterviewer.interview.service;

import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.sandbox.SandboxUnavailableException;
import com.techinterviewer.coding.testing.TestRunner;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.common.error.FieldViolation;
import com.techinterviewer.interview.api.dto.CodeRunRequest;
import com.techinterviewer.interview.api.dto.CodeRunResponse;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewQuestion;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Run examples": runs a draft against the visible examples of the open coding question. Nothing is stored and nothing is
 * evaluated, so it never changes the interview; the submitted answer is tested again, on every test, before its evaluation.
 * The sandbox runs outside any transaction.
 */
@Service
public class CodeRunService {

    private static final Duration BUSY_RETRY = Duration.ofSeconds(5);

    /** What the sandbox needs, read in a short transaction. */
    private record Target(CodingProblem problem, CodeLanguage language) {
    }

    private final InterviewTransactions transactions;
    private final InterviewLoader loader;
    private final ProblemBank bank;
    private final TestRunner runner;

    public CodeRunService(InterviewTransactions transactions, InterviewLoader loader, ProblemBank bank, TestRunner runner) {
        this.transactions = transactions;
        this.loader = loader;
        this.bank = bank;
        this.runner = runner;
    }

    /**
     * @throws ApiException {@code 404} for an interview or question that is not the caller's, {@code 409 QUESTION_NOT_CURRENT} when
     *                      the question is not the open one, {@code 409 CONFLICT} for a question without runnable examples, {@code 409
     *                      ILLEGAL_STATE_TRANSITION} when the interview does not take answers, {@code 503 CODE_EXECUTION_UNAVAILABLE}
     *                      when the sandbox cannot run it now
     * @throws FieldValidationException for a language that is not the interview's
     */
    public CodeRunResponse runExamples(UUID userId, UUID interviewId, UUID questionId, CodeRunRequest request) {
        Target target = transactions.read(() -> target(userId, interviewId, questionId, request.language()));
        TestRunReport report;
        try {
            report = runner.run(target.problem(), target.language(), request.code(), TestRunner.Scope.EXAMPLES);
        } catch (SandboxUnavailableException unavailable) {
            ApiException error = new ApiException(ErrorCode.CODE_EXECUTION_UNAVAILABLE, unavailable.getMessage());
            throw unavailable.busy() ? error.withRetryAfter(BUSY_RETRY) : error;
        }
        return new CodeRunResponse(report.status().name(), report.passed(), report.total(), CodeResultViews.of(report, target.problem()),
                report.compileOutput(), report.candidateOutput(), report.durationMs());
    }

    private Target target(UUID userId, UUID interviewId, UUID questionId, String language) {
        Interview interview = loader.load(interviewId, userId);
        InterviewQuestion question = loader.loadQuestion(interview, questionId);
        if (!interview.getState().acceptsAnswers()) {
            throw NotAccepting.error(interview, false);
        }
        if (!question.getId().equals(interview.getCurrentQuestionId())) {
            throw new ApiException(ErrorCode.QUESTION_NOT_CURRENT, "The interview has moved on; reload the session.")
                    .withProperty(TransitionApplier.CURRENT_STATE, interview.getState().name());
        }
        String interviewLanguage = loader.configurationOf(interview).getCodingLanguage();
        if (!language.equals(interviewLanguage)) {
            throw new FieldValidationException(List.of(new FieldViolation("language", "must be the language of the interview: " + interviewLanguage)));
        }
        if (question.getCodingProblemId() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "This question has no examples to run; submit the answer instead.");
        }
        CodingProblem problem = bank.find(question.getCodingProblemId())
                .orElseThrow(() -> new IllegalStateException("problem " + question.getCodingProblemId() + " is not in the bank"));
        CodeLanguage codeLanguage = CodeLanguage.fromCode(language)
                .orElseThrow(() -> new IllegalStateException("unknown coding language " + language));
        if (!runner.canRun(codeLanguage)) {
            throw new ApiException(ErrorCode.CODE_EXECUTION_UNAVAILABLE, runner.canRun(CodeLanguage.JAVA)
                    ? "Examples are not run for " + language + " on this server; your answer is reviewed by the AI interviewer."
                    : "Code execution is turned off on this server; your answer is reviewed by the AI interviewer.");
        }
        return new Target(problem, codeLanguage);
    }
}
