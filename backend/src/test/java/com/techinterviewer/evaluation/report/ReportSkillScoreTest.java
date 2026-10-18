package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.evaluation.domain.Confidence;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportSkillScoreTest {

    private static final UUID REPORT = UuidV7.generate();

    private static ReportSkillScore score(String score, int evidence, String difficulty) {
        return new ReportSkillScore(REPORT, 7L, new BigDecimal(score), Confidence.MEDIUM, evidence, new BigDecimal(difficulty));
    }

    @Test
    void aSkillScoreHoldsItsValuesRoundedToTheColumnScale() {
        ReportSkillScore skillScore = new ReportSkillScore(REPORT, 7L, new BigDecimal("81.255"), Confidence.HIGH, 4, new BigDecimal("3.254"));

        assertThat(skillScore.getId()).isEqualTo(new ReportSkillScoreId(REPORT, 7L));
        assertThat(skillScore.getReportId()).isEqualTo(REPORT);
        assertThat(skillScore.getSkillId()).isEqualTo(7L);
        assertThat(skillScore.getScore()).isEqualTo(new BigDecimal("81.26"));
        assertThat(skillScore.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(skillScore.getEvidenceCount()).isEqualTo(4);
        assertThat(skillScore.getAvgDifficulty()).isEqualTo(new BigDecimal("3.25"));
        assertThat(skillScore.isNew()).isTrue();
    }

    @Test
    void scoresAreEqualWhenTheyHaveTheSameReportAndSkill() {
        ReportSkillScore first = score("10", 1, "1");
        ReportSkillScore sameKey = score("90", 5, "5");
        ReportSkillScore otherSkill = new ReportSkillScore(REPORT, 8L, BigDecimal.TEN, Confidence.LOW, 1, BigDecimal.ONE);

        assertThat(first).isEqualTo(sameKey).hasSameHashCodeAs(sameKey).isNotEqualTo(otherSkill).isNotEqualTo(null);
        assertThat(first.toString()).contains("ReportSkillScore");
    }

    @Test
    void theBoundariesAreInclusiveAndOutsideValuesAreRefused() {
        assertThat(score("0", 0, "1").getScore()).isEqualByComparingTo("0");
        assertThat(score("100", 0, "5").getScore()).isEqualByComparingTo("100");
        assertThatIllegalArgumentException().isThrownBy(() -> score("100.01", 0, "3")).withMessageContaining("score");
        assertThatIllegalArgumentException().isThrownBy(() -> score("-0.01", 0, "3")).withMessageContaining("score");
        assertThatIllegalArgumentException().isThrownBy(() -> score("50", 0, "0.99")).withMessageContaining("avgDifficulty");
        assertThatIllegalArgumentException().isThrownBy(() -> score("50", 0, "5.01")).withMessageContaining("avgDifficulty");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 32768})
    void theEvidenceCountIsBoundedLikeASmallint(int count) {
        assertThatIllegalArgumentException().isThrownBy(() -> score("50", count, "3")).withMessageContaining("evidenceCount");
    }

    @Test
    void theLargestEvidenceCountThatFitsTheColumnIsAccepted() {
        assertThat(score("50", 32767, "3").getEvidenceCount()).isEqualTo(32767);
    }

    @Test
    void theKeyAndConfidenceAreRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportSkillScore(null, 7L, BigDecimal.TEN, Confidence.LOW, 0, BigDecimal.ONE)).withMessageContaining("reportId");
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportSkillScore(REPORT, 0L, BigDecimal.TEN, Confidence.LOW, 0, BigDecimal.ONE)).withMessageContaining("skillId");
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportSkillScore(REPORT, 7L, BigDecimal.TEN, null, 0, BigDecimal.ONE)).withMessageContaining("confidence");
    }
}
