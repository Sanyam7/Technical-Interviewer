package com.techinterviewer.evaluation.repository;

import com.techinterviewer.evaluation.report.ReportSkillScore;
import com.techinterviewer.evaluation.report.ReportSkillScoreId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access to {@link ReportSkillScore}. The caller owns the interview of the report. */
public interface ReportSkillScoreRepository extends JpaRepository<ReportSkillScore, ReportSkillScoreId> {

    /** Access rule: the skill scores of {@code reportId}, best score first, ties by skill id. */
    List<ReportSkillScore> findByIdReportIdOrderByScoreDescIdSkillIdAsc(UUID reportId);
}
