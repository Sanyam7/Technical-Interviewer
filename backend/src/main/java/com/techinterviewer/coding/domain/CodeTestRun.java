package com.techinterviewer.coding.domain;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The run of a submitted solution against all tests of its problem (table {@code code_test_runs}): at most one per answer
 * ({@code uq_code_test_runs_answer}), written once before the answer is evaluated and never changed. The evaluation's
 * scoring rules read it; the report shows it after the interview.
 */
@Entity
@Table(name = "code_test_runs")
public class CodeTestRun extends BaseEntity {

    @Column(name = "answer_id", nullable = false, updatable = false)
    private UUID answerId;

    @Column(name = "interview_id", nullable = false, updatable = false)
    private UUID interviewId;

    @Column(name = "problem_id", nullable = false, updatable = false, length = 60)
    private String problemId;

    @Column(name = "language", nullable = false, updatable = false, length = 20)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private RunStatus status;

    @Column(name = "passed", nullable = false, updatable = false)
    private int passed;

    @Column(name = "total", nullable = false, updatable = false)
    private int total;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcomes", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<TestOutcome> outcomes;

    @Column(name = "compile_output", updatable = false, columnDefinition = "text")
    private @Nullable String compileOutput;

    @Column(name = "candidate_output", nullable = false, updatable = false, columnDefinition = "text")
    private String candidateOutput;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CodeTestRun() {
    }

    public static CodeTestRun of(UUID answerId, UUID interviewId, TestRunReport report, Instant now) {
        CodeTestRun run = new CodeTestRun();
        run.answerId = Require.nonNull(answerId, "answerId");
        run.interviewId = Require.nonNull(interviewId, "interviewId");
        run.problemId = report.problemId();
        run.language = report.language();
        run.status = report.status();
        run.passed = report.passed();
        run.total = report.total();
        run.outcomes = report.outcomes();
        run.compileOutput = report.compileOutput();
        run.candidateOutput = report.candidateOutput();
        run.durationMs = report.durationMs();
        run.createdAt = Require.nonNull(now, "now");
        return run;
    }

    public TestRunReport report() {
        return new TestRunReport(problemId, language, status, outcomes, compileOutput, candidateOutput, durationMs);
    }

    public UUID getAnswerId() {
        return answerId;
    }

    public UUID getInterviewId() {
        return interviewId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
