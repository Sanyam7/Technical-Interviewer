package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The evaluation of one answer (table {@code evaluations}); exactly one per answer ({@code uq_evaluations_answer}),
 * written once and never changed. The overall score is computed by the server from the criterion ratings and weights
 * and handed in: it is never taken from the model. It is stored with two decimals, rounded half up.
 *
 * <p>That {@code evaluator} matches the kind of the answered question is checked by the evaluation service.
 */
@Entity
@Table(name = "evaluations")
public class Evaluation extends BaseEntity {

    @Column(name = "answer_id", nullable = false, updatable = false)
    private UUID answerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluator", nullable = false, updatable = false, length = 20)
    private Evaluator evaluator;

    @Column(name = "overall_score", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal overallScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "criterion_scores", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<CriterionScore> criterionScores;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_points", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<KeyPointAssessment> keyPoints;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factual_errors", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<FactualError> factualErrors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_claims", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<String> candidateClaims;

    @Column(name = "summary", nullable = false, updatable = false, columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Enumerated(EnumType.STRING)
    @Column(name = "flags", columnDefinition = "text[]", nullable = false, updatable = false)
    private Set<EvaluationFlag> flags;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluator_confidence", updatable = false, length = 10)
    private @Nullable Confidence evaluatorConfidence;

    @Column(name = "evaluator_confidence_reason", updatable = false, length = EvaluatorConfidence.MAX_REASON_LENGTH)
    private @Nullable String evaluatorConfidenceReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "code_analysis", columnDefinition = "jsonb", updatable = false)
    private @Nullable CodeAnalysis codeAnalysis;

    @Column(name = "prompt_id", nullable = false, updatable = false, length = 60)
    private String promptId;

    @Column(name = "prompt_version", nullable = false, updatable = false, length = 20)
    private String promptVersion;

    @Column(name = "model", nullable = false, updatable = false, length = 80)
    private String model;

    @Column(name = "llm_call_id", updatable = false)
    private @Nullable UUID llmCallId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Evaluation() {
    }

    private Evaluation(
            UUID answerId,
            Evaluator evaluator,
            BigDecimal overallScore,
            EvaluationFindings findings,
            ModelProvenance provenance,
            Instant createdAt) {
        this.answerId = answerId;
        this.evaluator = evaluator;
        this.overallScore = overallScore;
        this.criterionScores = findings.criterionScores();
        this.keyPoints = findings.keyPoints();
        this.factualErrors = findings.factualErrors();
        this.candidateClaims = findings.candidateClaims();
        this.summary = findings.summary();
        this.flags = findings.flags();
        if (findings.confidence() != null) {
            this.evaluatorConfidence = findings.confidence().level();
            this.evaluatorConfidenceReason = findings.confidence().reason();
        }
        this.codeAnalysis = findings.codeAnalysis();
        this.promptId = provenance.promptId();
        this.promptVersion = provenance.promptVersion();
        this.model = provenance.model();
        this.llmCallId = provenance.llmCallId();
        this.createdAt = createdAt;
    }

    /**
     * @param overallScore 0 to 100, computed by the server
     * @throws IllegalArgumentException when the score is outside 0 to 100 or a value is missing
     */
    public static Evaluation create(
            UUID answerId,
            Evaluator evaluator,
            BigDecimal overallScore,
            EvaluationFindings findings,
            ModelProvenance provenance,
            Instant now) {
        return new Evaluation(
                Require.nonNull(answerId, "answerId"),
                Require.nonNull(evaluator, "evaluator"),
                Scores.percent(overallScore, "overallScore"),
                Require.nonNull(findings, "findings"),
                Require.nonNull(provenance, "provenance"),
                Require.nonNull(now, "now"));
    }

    public UUID getAnswerId() {
        return answerId;
    }

    public Evaluator getEvaluator() {
        return evaluator;
    }

    /** 0 to 100 with two decimals. */
    public BigDecimal getOverallScore() {
        return overallScore;
    }

    public List<CriterionScore> getCriterionScores() {
        return Collections.unmodifiableList(criterionScores);
    }

    public List<KeyPointAssessment> getKeyPoints() {
        return Collections.unmodifiableList(keyPoints);
    }

    public List<FactualError> getFactualErrors() {
        return Collections.unmodifiableList(factualErrors);
    }

    public List<String> getCandidateClaims() {
        return Collections.unmodifiableList(candidateClaims);
    }

    public String getSummary() {
        return summary;
    }

    public Set<EvaluationFlag> getFlags() {
        return Collections.unmodifiableSet(flags);
    }

    /** How sure the evaluator was, after the server's adjustments; null when it did not say (see {@link EvaluatorConfidence}). */
    public @Nullable EvaluatorConfidence getEvaluatorConfidence() {
        return evaluatorConfidence == null ? null : new EvaluatorConfidence(evaluatorConfidence, evaluatorConfidenceReason);
    }

    /** The approach and complexity of a coding solution as the evaluator read them; null for other answers. */
    public @Nullable CodeAnalysis getCodeAnalysis() {
        return codeAnalysis;
    }

    public String getPromptId() {
        return promptId;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModel() {
        return model;
    }

    public @Nullable UUID getLlmCallId() {
        return llmCallId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
