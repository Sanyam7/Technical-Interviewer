package com.techinterviewer.evaluation.report;

import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link ReportSkillScore}: one score per skill in a report. */
@Embeddable
public record ReportSkillScoreId(
        @Column(name = "report_id", nullable = false, updatable = false) UUID reportId,
        @Column(name = "skill_id", nullable = false, updatable = false) long skillId) implements Serializable {

    public ReportSkillScoreId {
        Require.nonNull(reportId, "reportId");
        if (skillId <= 0) {
            throw new IllegalArgumentException("skillId must be positive but was " + skillId);
        }
    }
}
