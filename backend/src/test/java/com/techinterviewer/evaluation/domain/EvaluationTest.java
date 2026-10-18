package com.techinterviewer.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationTest {

    private static final UUID ANSWER = UuidV7.generate();

    @Test
    void anEvaluationHoldsItsFindingsProvenanceAndScore() {
        Evaluation evaluation = Evaluation.create(
                ANSWER, Evaluator.CODING, new BigDecimal("88.125"), Fixtures.findings(), Fixtures.provenance(), Fixtures.NOW);

        assertThat(evaluation.getAnswerId()).isEqualTo(ANSWER);
        assertThat(evaluation.getEvaluator()).isEqualTo(Evaluator.CODING);
        assertThat(evaluation.getOverallScore()).as("rounded half up to two decimals").isEqualTo(new BigDecimal("88.13"));
        assertThat(evaluation.getCriterionScores()).isEqualTo(Fixtures.findings().criterionScores());
        assertThat(evaluation.getKeyPoints()).isEqualTo(Fixtures.findings().keyPoints());
        assertThat(evaluation.getFactualErrors()).isEqualTo(Fixtures.findings().factualErrors());
        assertThat(evaluation.getCandidateClaims()).isEqualTo(Fixtures.findings().candidateClaims());
        assertThat(evaluation.getSummary()).isEqualTo(Fixtures.findings().summary());
        assertThat(evaluation.getFlags()).isEqualTo(Fixtures.findings().flags());
        assertThat(evaluation.getPromptId()).isEqualTo(Fixtures.provenance().promptId());
        assertThat(evaluation.getPromptVersion()).isEqualTo(Fixtures.provenance().promptVersion());
        assertThat(evaluation.getModel()).isEqualTo(Fixtures.provenance().model());
        assertThat(evaluation.getLlmCallId()).isEqualTo(Fixtures.provenance().llmCallId());
        assertThat(evaluation.getCreatedAt()).isEqualTo(Fixtures.NOW);
        assertThat(UuidV7.isV7(evaluation.getId())).isTrue();
    }

    @Test
    void theScoreBoundariesAreInclusive() {
        assertThat(score("0").getOverallScore()).isEqualByComparingTo("0");
        assertThat(score("100").getOverallScore()).isEqualByComparingTo("100");
        assertThatIllegalArgumentException().isThrownBy(() -> score("-0.01")).withMessageContaining("overallScore");
        assertThatIllegalArgumentException().isThrownBy(() -> score("100.01")).withMessageContaining("overallScore");
    }

    private static Evaluation score(String value) {
        return Evaluation.create(ANSWER, Evaluator.TECHNICAL, new BigDecimal(value), Fixtures.findings(), Fixtures.provenance(), Fixtures.NOW);
    }

    @Test
    void requiredValuesAreChecked() {
        EvaluationFindings findings = Fixtures.findings();
        ModelProvenance provenance = Fixtures.provenance();
        BigDecimal ten = BigDecimal.TEN;

        assertThatIllegalArgumentException().isThrownBy(() -> Evaluation.create(null, Evaluator.INTRO, ten, findings, provenance, Fixtures.NOW)).withMessageContaining("answerId");
        assertThatIllegalArgumentException().isThrownBy(() -> Evaluation.create(ANSWER, null, ten, findings, provenance, Fixtures.NOW)).withMessageContaining("evaluator");
        assertThatIllegalArgumentException().isThrownBy(() -> Evaluation.create(ANSWER, Evaluator.INTRO, null, findings, provenance, Fixtures.NOW)).withMessageContaining("overallScore");
        assertThatIllegalArgumentException().isThrownBy(() -> Evaluation.create(ANSWER, Evaluator.INTRO, ten, null, provenance, Fixtures.NOW)).withMessageContaining("findings");
        assertThatIllegalArgumentException().isThrownBy(() -> Evaluation.create(ANSWER, Evaluator.INTRO, ten, findings, null, Fixtures.NOW)).withMessageContaining("provenance");
        assertThatIllegalArgumentException().isThrownBy(() -> Evaluation.create(ANSWER, Evaluator.INTRO, ten, findings, provenance, null)).withMessageContaining("now");
    }

    @Test
    void theCollectionsCannotBeModifiedThroughTheGetters() {
        Evaluation evaluation = Fixtures.evaluation(ANSWER);

        assertThatThrownBy(() -> evaluation.getCriterionScores().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evaluation.getKeyPoints().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evaluation.getFactualErrors().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evaluation.getCandidateClaims().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evaluation.getFlags().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
