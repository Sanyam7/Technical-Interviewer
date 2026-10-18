package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Configurable weights and skill profiles: what scores an answer, and the validation that keeps a bad table from starting. */
class ScoringPolicyTest {

    private static final Map<Criterion, BigDecimal> FLAT_TECHNICAL = AnswerScorerTest.weights("0.20", "0.10", "0.20", "0.20", "0.20", "0.10");

    @Test
    void withoutConfigurationTheBuiltInRubricsApplyAndNoSkillHasAProfile() {
        ScoringPolicy policy = ScoringPolicy.defaults();

        for (Evaluator evaluator : Evaluator.values()) {
            assertThat(policy.rubric(evaluator, "java")).isEqualTo(RubricRegistry.forEvaluator(evaluator));
            assertThat(policy.focus(evaluator, "java")).isEmpty();
        }
        assertThat(policy.profiles()).isEmpty();
    }

    @Test
    void aBaseTableReplacesTheDefaultOfItsEvaluatorOnly() {
        ScoringPolicy policy = ScoringPolicy.of(Map.of(Evaluator.TECHNICAL, FLAT_TECHNICAL), Map.of());

        Rubric technical = policy.rubric(Evaluator.TECHNICAL, null);
        assertThat(technical.id()).as("same criteria, same contract").isEqualTo("technical.v2");
        assertThat(technical.weights()).extracting(CriterionWeight::weight).map(BigDecimal::toPlainString)
                .containsExactly("0.20", "0.10", "0.20", "0.20", "0.20", "0.10");
        assertThat(policy.rubric(Evaluator.CODING, null)).isEqualTo(RubricRegistry.forEvaluator(Evaluator.CODING));
    }

    @Test
    void aSkillProfileReplacesTheWeightsOfTheEvaluatorsItNamesAndGivesItsFocusAreas() {
        ScoringPolicy policy = ScoringPolicy.of(Map.of(), Map.of(
                "react", new ScoringPolicy.SkillSpec(List.of(" state management ", "rendering performance"), Map.of(Evaluator.TECHNICAL, FLAT_TECHNICAL))));

        assertThat(policy.rubric(Evaluator.TECHNICAL, "react").id()).isEqualTo("technical.v2+react");
        assertThat(policy.rubric(Evaluator.CODING, "react")).as("not named: base").isEqualTo(RubricRegistry.forEvaluator(Evaluator.CODING));
        assertThat(policy.focus(Evaluator.TECHNICAL, "react")).containsExactly("state management", "rendering performance");
        assertThat(policy.focus(Evaluator.INTRO, "react")).as("the introduction is not technical").isEmpty();
        assertThat(policy.rubric(Evaluator.TECHNICAL, "java")).as("no profile").isEqualTo(RubricRegistry.forEvaluator(Evaluator.TECHNICAL));
    }

    @Test
    void theProfileOfAQuestionIsItsSkillTheLanguageOfCodeOrSystemDesignForADesignProblem() {
        assertThat(ScoringPolicy.profileKey(Evaluator.TECHNICAL, "react", null)).isEqualTo("react");
        assertThat(ScoringPolicy.profileKey(Evaluator.TECHNICAL, null, null)).isNull();
        assertThat(ScoringPolicy.profileKey(Evaluator.CODING, null, " Java ")).isEqualTo("java");
        assertThat(ScoringPolicy.profileKey(Evaluator.CODING, "typescript", null)).isEqualTo("typescript");
        assertThat(ScoringPolicy.profileKey(Evaluator.SYSTEM_DESIGN, null, null)).isEqualTo("system-design");
        assertThat(ScoringPolicy.profileKey(Evaluator.SYSTEM_DESIGN, "postgresql", null)).isEqualTo("postgresql");
        assertThat(ScoringPolicy.profileKey(Evaluator.INTRO, "java", "java")).isNull();
    }

    @Test
    void theStartupLogListsEveryTable() {
        ScoringPolicy policy = ScoringPolicy.of(Map.of(), Map.of(
                "react", new ScoringPolicy.SkillSpec(List.of("state management"), Map.of(Evaluator.TECHNICAL, FLAT_TECHNICAL))));

        assertThat(policy.describe()).contains(
                "technical.v2: CORRECTNESS=0.30 COMPLETENESS=0.10 DEPTH=0.20 PRACTICAL_UNDERSTANDING=0.15 REASONING=0.15 COMMUNICATION=0.10",
                "technical.v2+react: CORRECTNESS=0.20 COMPLETENESS=0.10 DEPTH=0.20 PRACTICAL_UNDERSTANDING=0.20 REASONING=0.20 COMMUNICATION=0.10",
                "react focus: state management");
    }

    @Nested
    class Validation {

        @Test
        void aTableMustWeighExactlyTheCriteriaOfItsEvaluator() {
            Map<Criterion, BigDecimal> wrong = new EnumMap<>(FLAT_TECHNICAL);
            wrong.remove(Criterion.REASONING);
            wrong.put(Criterion.CODE_QUALITY, new BigDecimal("0.20"));

            assertThatThrownBy(() -> ScoringPolicy.of(Map.of(Evaluator.TECHNICAL, wrong), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("app.evaluation.scoring.weights.technical: must weigh exactly the criteria")
                    .hasMessageContaining("missing [REASONING], not rated by this evaluator [CODE_QUALITY]");
        }

        @Test
        void aTableMustAddUpToExactlyOne() {
            Map<Criterion, BigDecimal> heavy = new EnumMap<>(FLAT_TECHNICAL);
            heavy.put(Criterion.COMMUNICATION, new BigDecimal("0.11"));

            assertThatThrownBy(() -> ScoringPolicy.of(Map.of(), Map.of("java", new ScoringPolicy.SkillSpec(null, Map.of(Evaluator.TECHNICAL, heavy)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("app.evaluation.scoring.skills.java.weights.technical: the weights must add up to 1 but add up to 1.01");
        }

        @Test
        void aWeightMustBePositive() {
            Map<Criterion, BigDecimal> zero = new EnumMap<>(FLAT_TECHNICAL);
            zero.put(Criterion.COMMUNICATION, BigDecimal.ZERO);
            zero.put(Criterion.CORRECTNESS, new BigDecimal("0.30"));

            assertThatThrownBy(() -> ScoringPolicy.of(Map.of(Evaluator.TECHNICAL, zero), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("app.evaluation.scoring.weights.technical: ")
                    .hasMessageContaining("weight must be above 0");
        }

        @Test
        void theIntroductionHasNoSkillProfile() {
            assertThatThrownBy(() -> ScoringPolicy.of(Map.of(), Map.of("java", new ScoringPolicy.SkillSpec(null,
                    Map.of(Evaluator.INTRO, Map.of(Criterion.COMMUNICATION, BigDecimal.ONE))))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("skills.java.weights.intro: the introduction is not technical");
        }

        @Test
        void profileKeysAreLowerCaseSlugsAndFocusAreasAreShortAndFew() {
            assertThatThrownBy(() -> ScoringPolicy.of(Map.of(), Map.of("Java", new ScoringPolicy.SkillSpec(List.of("x"), null))))
                    .hasMessageContaining("skills.Java: a profile key is a skill slug or a language in lower case");
            assertThatThrownBy(() -> ScoringPolicy.of(Map.of(), Map.of("java", new ScoringPolicy.SkillSpec(List.of("x".repeat(121)), null))))
                    .hasMessageContaining("skills.java.focus: at most 8 areas of 1 to 120 characters");
            assertThatThrownBy(() -> ScoringPolicy.of(Map.of(), Map.of("java", new ScoringPolicy.SkillSpec(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"), null))))
                    .hasMessageContaining("skills.java.focus");
            assertThatThrownBy(() -> ScoringPolicy.of(Map.of(), Map.of("java", new ScoringPolicy.SkillSpec(List.of(" "), null))))
                    .hasMessageContaining("skills.java.focus");
        }

        @Test
        void aProfileWithFocusOnlyKeepsTheBaseWeights() {
            ScoringPolicy policy = ScoringPolicy.of(Map.of(), Map.of("git", new ScoringPolicy.SkillSpec(List.of("branching"), null)));

            assertThat(policy.rubric(Evaluator.TECHNICAL, "git")).isEqualTo(RubricRegistry.forEvaluator(Evaluator.TECHNICAL));
            assertThat(policy.focus(Evaluator.TECHNICAL, "git")).containsExactly("branching");
        }
    }

    /** The shipped configuration: it must bind, validate, and say what the architecture document says it does. */
    @Nested
    class ShippedConfiguration {

        private final ScoringPolicy shipped = ShippedScoring.policy();

        private static Criterion heaviest(Rubric rubric) {
            return rubric.weights().stream().max(Comparator.comparing(CriterionWeight::weight)).orElseThrow().criterion();
        }

        private static BigDecimal weight(Rubric rubric, Criterion criterion) {
            return rubric.weights().stream().filter(weight -> weight.criterion() == criterion).findFirst().orElseThrow().weight();
        }

        @Test
        void everyCatalogSkillHasAProfileWithFocusAreasAndTechnicalWeights() {
            assertThat(shipped.profiles().keySet())
                    .containsExactlyInAnyOrder("java", "spring-boot", "postgresql", "react", "typescript", "system-design", "javascript");
            shipped.profiles().values().forEach(profile -> {
                assertThat(profile.focus()).as(profile.key()).isNotEmpty();
                if (!profile.key().equals("javascript")) {
                    assertThat(profile.rubrics()).as(profile.key()).containsKey(Evaluator.TECHNICAL);
                }
            });
            // JavaScript is a coding language without a catalog skill: its profile only names what matters to the evaluator.
            assertThat(shipped.profile("javascript").orElseThrow().rubrics()).isEmpty();
            assertThat(shipped.base()).isEqualTo(ScoringPolicy.defaults().base());
        }

        @Test
        void javaWeighsPreciseLanguageKnowledgeMore() {
            Rubric java = shipped.rubric(Evaluator.TECHNICAL, "java");
            Rubric base = shipped.rubric(Evaluator.TECHNICAL, null);

            assertThat(weight(java, Criterion.CORRECTNESS)).isGreaterThan(weight(base, Criterion.CORRECTNESS));
            assertThat(weight(java, Criterion.DEPTH)).isGreaterThan(weight(base, Criterion.DEPTH));
            assertThat(weight(java, Criterion.COMMUNICATION)).isLessThan(weight(base, Criterion.COMMUNICATION));
            assertThat(shipped.rubric(Evaluator.CODING, ScoringPolicy.profileKey(Evaluator.CODING, null, "java")).id())
                    .as("a Java coding answer").isEqualTo("coding.v2+java");
        }

        @Test
        void reactWeighsPracticalUnderstandingOfComponentsStateAndRenderingMore() {
            Rubric react = shipped.rubric(Evaluator.TECHNICAL, "react");

            assertThat(weight(react, Criterion.PRACTICAL_UNDERSTANDING)).isGreaterThan(weight(shipped.rubric(Evaluator.TECHNICAL, null), Criterion.PRACTICAL_UNDERSTANDING));
            assertThat(shipped.focus(Evaluator.TECHNICAL, "react")).anyMatch(area -> area.contains("component architecture"))
                    .anyMatch(area -> area.contains("state management")).anyMatch(area -> area.contains("performance"));
        }

        @Test
        void systemDesignWeighsArchitectureAndScalabilityMost() {
            Rubric design = shipped.rubric(Evaluator.SYSTEM_DESIGN, ScoringPolicy.profileKey(Evaluator.SYSTEM_DESIGN, null, null));

            List<Criterion> topTwo = design.weights().stream().sorted(Comparator.comparing(CriterionWeight::weight).reversed())
                    .limit(2).map(CriterionWeight::criterion).toList();
            assertThat(Set.copyOf(topTwo)).containsExactlyInAnyOrder(Criterion.HIGH_LEVEL_DESIGN, Criterion.SCALABILITY);
            assertThat(heaviest(shipped.rubric(Evaluator.TECHNICAL, "system-design"))).as("design questions are argued")
                    .isEqualTo(Criterion.REASONING);
        }
    }
}
