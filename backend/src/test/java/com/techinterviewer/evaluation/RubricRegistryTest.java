package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class RubricRegistryTest {

    private static Map<Criterion, String> weightsOf(Evaluator evaluator) {
        Map<Criterion, String> weights = new LinkedHashMap<>();
        RubricRegistry.forEvaluator(evaluator).weights()
                .forEach(weight -> weights.put(weight.criterion(), weight.weight().toPlainString()));
        return weights;
    }

    @Test
    void technicalWeightsAreTheOnesOfTheArchitectureDocument() {
        assertThat(weightsOf(Evaluator.TECHNICAL)).containsExactly(
                Map.entry(Criterion.CORRECTNESS, "0.30"),
                Map.entry(Criterion.COMPLETENESS, "0.10"),
                Map.entry(Criterion.DEPTH, "0.20"),
                Map.entry(Criterion.PRACTICAL_UNDERSTANDING, "0.15"),
                Map.entry(Criterion.REASONING, "0.15"),
                Map.entry(Criterion.COMMUNICATION, "0.10"));
    }

    @Test
    void theCriteriaOfAnEvaluatorAreTheOnesOfItsDefaultRubric() {
        for (Evaluator evaluator : Evaluator.values()) {
            assertThat(RubricRegistry.criteria(evaluator)).isEqualTo(RubricRegistry.forEvaluator(evaluator).criteria());
        }
    }

    @Test
    void introductionIsRatedOnCommunicationOnly() {
        assertThat(weightsOf(Evaluator.INTRO)).containsExactly(Map.entry(Criterion.COMMUNICATION, "1.00"));
    }

    @Test
    void codingRubricUsesTheSevenCodingCriteria() {
        assertThat(weightsOf(Evaluator.CODING)).containsExactly(
                Map.entry(Criterion.CORRECTNESS, "0.35"),
                Map.entry(Criterion.ALGORITHM, "0.15"),
                Map.entry(Criterion.TIME_COMPLEXITY, "0.15"),
                Map.entry(Criterion.SPACE_COMPLEXITY, "0.05"),
                Map.entry(Criterion.EDGE_CASES, "0.10"),
                Map.entry(Criterion.READABILITY, "0.10"),
                Map.entry(Criterion.MAINTAINABILITY, "0.10"));
    }

    @Test
    void systemDesignRubricRatesThirteenDimensionsWithArchitectureAndScalabilityWeighingMost() {
        assertThat(weightsOf(Evaluator.SYSTEM_DESIGN)).containsExactly(
                Map.entry(Criterion.REQUIREMENTS, "0.10"),
                Map.entry(Criterion.HIGH_LEVEL_DESIGN, "0.12"),
                Map.entry(Criterion.API_DESIGN, "0.06"),
                Map.entry(Criterion.DATA_MODEL, "0.10"),
                Map.entry(Criterion.CACHING, "0.07"),
                Map.entry(Criterion.MESSAGING, "0.06"),
                Map.entry(Criterion.SCALABILITY, "0.12"),
                Map.entry(Criterion.AVAILABILITY, "0.07"),
                Map.entry(Criterion.RELIABILITY, "0.07"),
                Map.entry(Criterion.FAILURE_HANDLING, "0.07"),
                Map.entry(Criterion.SECURITY, "0.05"),
                Map.entry(Criterion.OBSERVABILITY, "0.04"),
                Map.entry(Criterion.TRADE_OFFS, "0.07"));
    }

    @ParameterizedTest
    @EnumSource(Evaluator.class)
    void everyRubricBelongsToItsEvaluatorAndItsWeightsAddUpToOne(Evaluator evaluator) {
        Rubric rubric = RubricRegistry.forEvaluator(evaluator);

        assertThat(rubric.evaluator()).isEqualTo(evaluator);
        assertThat(rubric.weights().stream().map(CriterionWeight::weight).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1");
        assertThat(rubric.id()).isNotBlank();
    }

    @Test
    void rubricIdsAreDistinctBecauseTheyAreStoredWithTheQuestion() {
        List<String> ids = List.of(Evaluator.values()).stream().map(e -> RubricRegistry.forEvaluator(e).id()).toList();

        assertThat(ids).containsExactly("intro.v1", "technical.v2", "coding.v2", "system-design.v2");
    }

    @Test
    void everyCriterionIsUsedByAtLeastOneRubricExceptThoseKeptForTheRowsOfTheFirstCodingAndDesignRubrics() {
        Set<Criterion> used = EnumSet.noneOf(Criterion.class);
        for (Evaluator evaluator : Evaluator.values()) {
            used.addAll(RubricRegistry.forEvaluator(evaluator).criteria());
        }
        Set<Criterion> unused = EnumSet.allOf(Criterion.class);
        unused.removeAll(used);

        assertThat(unused).containsExactlyInAnyOrder(Criterion.ALGORITHMIC_EFFICIENCY, Criterion.CODE_QUALITY, Criterion.RELIABILITY_AND_TRADEOFFS);
    }

    @ParameterizedTest
    @CsvSource({
        "INTRO, INTRODUCTION, INTRO",
        "TECHNICAL, QUESTIONING, TECHNICAL",
        "CODING, CODING, CODING",
        "SYSTEM_DESIGN, SYSTEM_DESIGN, SYSTEM_DESIGN",
        "FOLLOW_UP, QUESTIONING, TECHNICAL",
        "FOLLOW_UP, CODING, CODING",
        "FOLLOW_UP, SYSTEM_DESIGN, SYSTEM_DESIGN"
    })
    void evaluatorFollowsTheQuestionKindAndForFollowUpsTheThreadSection(QuestionKind kind, Section section, Evaluator expected) {
        assertThat(RubricRegistry.evaluatorFor(kind, section)).isEqualTo(expected);
    }

    @Test
    void aFollowUpInTheIntroductionHasNoEvaluator() {
        assertThatThrownBy(() -> RubricRegistry.evaluatorFor(QuestionKind.FOLLOW_UP, Section.INTRODUCTION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rubricRejectsWeightsThatDoNotAddUpToOne() {
        assertThatThrownBy(() -> new Rubric("x.v1", Evaluator.TECHNICAL, List.of(
                CriterionWeight.of(Criterion.CORRECTNESS, "0.50"), CriterionWeight.of(Criterion.DEPTH, "0.49"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add up to 1");
        assertThatThrownBy(() -> new Rubric("x.v1", Evaluator.TECHNICAL, List.of(
                CriterionWeight.of(Criterion.CORRECTNESS, "0.50"), CriterionWeight.of(Criterion.DEPTH, "0.51"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rubricRejectsADuplicateCriterionAndAnEmptyList() {
        assertThatThrownBy(() -> new Rubric("x.v1", Evaluator.TECHNICAL, List.of(
                CriterionWeight.of(Criterion.CORRECTNESS, "0.50"), CriterionWeight.of(Criterion.CORRECTNESS, "0.50"))))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> new Rubric("x.v1", Evaluator.TECHNICAL, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weightsMustBePositiveAndAtMostOne() {
        assertThatThrownBy(() -> CriterionWeight.of(Criterion.DEPTH, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CriterionWeight.of(Criterion.DEPTH, "-0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CriterionWeight.of(Criterion.DEPTH, "1.01")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CriterionWeight.of(Criterion.DEPTH, "1").weight()).isEqualByComparingTo("1");
        assertThat(CriterionWeight.of(Criterion.DEPTH, "0.01").weight()).isEqualByComparingTo("0.01");
    }
}
