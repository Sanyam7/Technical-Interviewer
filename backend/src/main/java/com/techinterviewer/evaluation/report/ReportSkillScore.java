package com.techinterviewer.evaluation.report;

import com.techinterviewer.common.persistence.AssignedIdEntity;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Scores;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The score of one skill in a report (table {@code report_skill_scores}), relational so that progress per skill
 * over time stays a simple query. Written once with the report and never changed.
 */
@Entity
@Table(name = "report_skill_scores")
public class ReportSkillScore extends AssignedIdEntity<ReportSkillScoreId> {

    @EmbeddedId
    private ReportSkillScoreId id;

    @Column(name = "score", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, updatable = false, length = 10)
    private Confidence confidence;

    @Column(name = "evidence_count", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int evidenceCount;

    @Column(name = "avg_difficulty", nullable = false, updatable = false, precision = 3, scale = 2)
    private BigDecimal avgDifficulty;

    protected ReportSkillScore() {
    }

    /**
     * @param score          0 to 100
     * @param evidenceCount  how many answers back the score, not negative
     * @param avgDifficulty  average difficulty of those answers, 1 to 5
     */
    public ReportSkillScore(
            UUID reportId,
            long skillId,
            BigDecimal score,
            Confidence confidence,
            int evidenceCount,
            BigDecimal avgDifficulty) {
        this.id = new ReportSkillScoreId(reportId, skillId);
        this.score = Scores.percent(score, "score");
        this.confidence = Require.nonNull(confidence, "confidence");
        this.evidenceCount = Require.between(evidenceCount, 0, Short.MAX_VALUE, "evidenceCount");
        this.avgDifficulty = Scores.difficulty(avgDifficulty, "avgDifficulty");
    }

    @Override
    public ReportSkillScoreId getId() {
        return id;
    }

    public UUID getReportId() {
        return id.reportId();
    }

    public long getSkillId() {
        return id.skillId();
    }

    public BigDecimal getScore() {
        return score;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    public int getEvidenceCount() {
        return evidenceCount;
    }

    public BigDecimal getAvgDifficulty() {
        return avgDifficulty;
    }
}
