package com.techinterviewer.coding.service;

import com.techinterviewer.coding.domain.CodeTestRun;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.repository.CodeTestRunRepository;
import com.techinterviewer.coding.sandbox.SandboxUnavailableException;
import com.techinterviewer.coding.testing.TestRunner;
import com.techinterviewer.common.persistence.NoTransaction;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tests of submitted coding solutions. A solution to a problem from the bank is run against all its tests once,
 * before its first evaluation attempt, and the result is stored ({@code code_test_runs}); the evaluation and the report
 * read it from there. The sandbox runs outside any transaction (it takes seconds); the result is stored in a short one.
 * When two requests run the same solution at once, the first stored result wins.
 */
@Service
public class CodeSubmissions {

    /** What a solution needs to be tested: the answer, its problem and its language. */
    private record Pending(CandidateAnswer answer, CodingProblem problem, CodeLanguage language) {
    }

    private static final Logger log = LoggerFactory.getLogger(CodeSubmissions.class);

    private final CandidateAnswerRepository answers;
    private final InterviewQuestionRepository questions;
    private final InterviewRepository interviews;
    private final InterviewConfigurationRepository configurations;
    private final CodeTestRunRepository runs;
    private final ProblemBank bank;
    private final TestRunner runner;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public CodeSubmissions(CandidateAnswerRepository answers, InterviewQuestionRepository questions, InterviewRepository interviews,
            InterviewConfigurationRepository configurations, CodeTestRunRepository runs, ProblemBank bank, TestRunner runner, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.answers = answers;
        this.questions = questions;
        this.interviews = interviews;
        this.configurations = configurations;
        this.runs = runs;
        this.bank = bank;
        this.runner = runner;
        this.clock = clock;
        this.writes = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
    }

    /**
     * Runs the tests of a pending solution to a bank problem, unless they ran already. Does nothing for any other answer, or
     * when the tests cannot run here (execution off, a language without a harness, an unavailable or failing sandbox): the
     * evaluation then caps the correctness as unverified. Never throws because of the sandbox.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when called inside a transaction
     */
    public void ensureTested(UUID interviewId, UUID answerId) {
        NoTransaction.require("Running the tests of a solution");
        Pending pending = reads.execute(status -> pending(interviewId, answerId));
        if (pending == null) {
            return;
        }
        TestRunReport report;
        try {
            report = runner.run(pending.problem(), pending.language(), pending.answer().getContent(), TestRunner.Scope.ALL);
        } catch (SandboxUnavailableException unavailable) {
            log.warn("The tests of answer {} could not run: {}", answerId, unavailable.getMessage());
            return;
        } catch (RuntimeException failure) {
            // The answer is stored already: a broken run must never fail the turn. The evaluation proceeds without tests (capped).
            log.error("Running the tests of answer {} failed unexpectedly", answerId, failure);
            return;
        }
        try {
            writes.executeWithoutResult(status -> runs.saveAndFlush(CodeTestRun.of(answerId, interviewId, report, clock.instant())));
            log.info("Answer {} ({}, {}): {} of {} tests passed, {}", answerId, report.problemId(), report.language(), report.passed(),
                    report.total(), report.status());
        } catch (DataIntegrityViolationException concurrent) {
            log.info("The tests of answer {} were stored by a concurrent request", answerId);
        }
    }

    private @Nullable Pending pending(UUID interviewId, UUID answerId) {
        CandidateAnswer answer = answers.findByIdAndInterviewId(answerId, interviewId).orElse(null);
        if (answer == null || answer.isSkipped() || answer.getEvaluationStatus() != EvaluationStatus.PENDING || runs.existsByAnswerId(answerId)) {
            return null;
        }
        InterviewQuestion question = questions.findByIdAndInterviewId(answer.getQuestionId(), interviewId).orElse(null);
        if (question == null || question.getKind() != QuestionKind.CODING || question.getCodingProblemId() == null) {
            return null;
        }
        Optional<CodingProblem> problem = bank.find(question.getCodingProblemId());
        Optional<CodeLanguage> language = Optional.ofNullable(languageOf(answer, interviewId)).flatMap(CodeLanguage::fromCode);
        if (problem.isEmpty() || language.isEmpty() || !runner.canRun(language.get())) {
            return null;
        }
        return new Pending(answer, problem.get(), language.get());
    }

    private @Nullable String languageOf(CandidateAnswer answer, UUID interviewId) {
        if (answer.getCodeLanguage() != null) {
            return answer.getCodeLanguage();
        }
        return interviews.findById(interviewId)
                .flatMap(interview -> configurations.findById(interview.getConfigurationId()))
                .map(InterviewConfiguration::getCodingLanguage)
                .orElse(null);
    }

    /** The stored test run of an answer, in the caller's transaction. */
    public Optional<TestRunReport> stored(UUID answerId) {
        return runs.findByAnswerId(answerId).map(CodeTestRun::report);
    }

    /** The stored test runs of several answers, by answer id. */
    public Map<UUID, TestRunReport> stored(Collection<UUID> answerIds) {
        List<CodeTestRun> found = answerIds.isEmpty() ? List.of() : runs.findByAnswerIdIn(answerIds);
        Map<UUID, TestRunReport> byAnswer = new HashMap<>();
        found.forEach(run -> byAnswer.put(run.getAnswerId(), run.report()));
        return byAnswer;
    }

    /** Whether solutions in this language are run here. */
    public boolean testable(String codingLanguage) {
        return CodeLanguage.fromCode(codingLanguage).map(runner::canRun).orElse(false);
    }
}
