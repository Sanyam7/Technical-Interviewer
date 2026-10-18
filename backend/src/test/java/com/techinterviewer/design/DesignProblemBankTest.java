package com.techinterviewer.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionOrigin;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The curated system design problems (AI_ARCHITECTURE.md section 2.12): they load, validate and cover every level. */
class DesignProblemBankTest {

    private static final DesignProblemBank BANK = DesignProblemBank.load();

    @Test
    void theBankHasTheRealisticProblemsAndEveryLevelHasAtLeastTwo() {
        assertThat(BANK.all()).extracting(DesignProblem::id).containsExactlyInAnyOrder("url-shortener", "pastebin", "rate-limiter",
                "notification-service", "chat-system", "food-delivery", "social-feed", "video-streaming", "payment-system");
        IntStream.rangeClosed(1, 5).forEach(level ->
                assertThat(BANK.all().stream().filter(problem -> problem.fits(level)).count()).as("level " + level).isGreaterThanOrEqualTo(2));
    }

    @Test
    void everyProblemGivesFactsKeyPointsTheEvaluatorCanJudgeAndScenariosThatAreQuestions() {
        for (DesignProblem problem : BANK.all()) {
            assertThat(problem.facts()).as(problem.id()).isNotEmpty();
            assertThat(problem.keyPoints()).as(problem.id()).hasSizeBetween(6, DesignProblem.MAX_KEY_POINTS);
            assertThat(problem.probes()).as(problem.id() + " has scenarios of its own").hasSizeGreaterThanOrEqualTo(5);
            assertThat(problem.statement()).as(problem.id()).contains("assumptions");
            for (Criterion criterion : DesignProbes.DIMENSIONS) {
                assertThat(problem.probe(criterion)).as(problem.id() + " " + criterion).endsWith("?");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Criterion.class)
    void everyDimensionOfTheRubricHasAMeaningAndAGenericProbe(Criterion criterion) {
        if (RubricRegistry.criteria(Evaluator.SYSTEM_DESIGN).contains(criterion)) {
            assertThat(DesignProbes.meaning(criterion)).isNotBlank();
            assertThat(DesignProbes.generic(criterion)).endsWith("?");
        } else {
            assertThatThrownBy(() -> DesignProbes.generic(criterion)).hasMessageContaining("not a dimension");
        }
    }

    @Test
    void theClassicQuestionsAreTheGenericProbes() {
        assertThat(DesignProbes.generic(Criterion.SCALABILITY)).startsWith("What happens if traffic increases 100x?");
        assertThat(DesignProbes.generic(Criterion.FAILURE_HANDLING)).startsWith("How would you handle a database failure?");
        assertThat(DesignProbes.generic(Criterion.DATA_MODEL)).startsWith("Would you use SQL or NoSQL");
        assertThat(DesignProbes.generic(Criterion.CACHING)).startsWith("Where would you introduce caching");
    }

    @Test
    void aProblemIsChosenAtItsLevelOnceAndTheSameForTheSameInterview() {
        UUID interview = UUID.randomUUID();
        DesignProblem chosen = BANK.choose(3, Set.of(), interview.getLeastSignificantBits()).orElseThrow();

        assertThat(chosen.fits(3)).isTrue();
        assertThat(BANK.choose(3, Set.of(), interview.getLeastSignificantBits())).contains(chosen);
        assertThat(BANK.choose(3, Set.of(chosen.id()), interview.getLeastSignificantBits())).get().isNotEqualTo(chosen);
        List<String> all = BANK.all().stream().map(DesignProblem::id).toList();
        assertThat(BANK.choose(3, Set.copyOf(all), 1)).isEmpty();
    }

    @Test
    void aChosenProblemBecomesStaticContentWithItsFactsAndKeyPoints() {
        QuestionContent content = new DesignQuestions(BANK).choose(UUID.randomUUID(), 5, Set.of(), "system-design.v2").orElseThrow();

        assertThat(content.origin()).isEqualTo(QuestionOrigin.STATIC);
        assertThat(content.designProblemId()).isNotNull();
        DesignProblem problem = BANK.find(content.designProblemId()).orElseThrow();
        assertThat(content.prompt()).isEqualTo(problem.statement());
        assertThat(content.details().constraints()).isEqualTo(problem.facts());
        assertThat(content.expectedKeyPoints()).isEqualTo(problem.keyPoints());
        assertThat(new DesignQuestions(BANK).probe(problem.id(), Criterion.SECURITY)).isEqualTo(problem.probe(Criterion.SECURITY));
        assertThat(new DesignQuestions(BANK).probe(null, Criterion.SECURITY)).isEqualTo(DesignProbes.generic(Criterion.SECURITY));
    }

    private static final String VALID = """
            {"id": "toy", "title": "Design a toy", "levels": [1, 2],
             "statement": "Design a toy service that does one thing well for a few users. State the assumptions you need and walk through it.",
             "facts": ["10 users"],
             "keyPoints": [{"id": "kp1", "text": "a"}, {"id": "kp2", "text": "b"}, {"id": "kp3", "text": "c"}, {"id": "kp4", "text": "d"}],
             "probes": {"CACHING": "Where would you cache?"}}""";

    @Test
    void aFileThatDoesNotValidateStopsTheApplication() {
        assertThat(DesignProblemBank.parse(VALID, "toy.json").probe(Criterion.CACHING)).isEqualTo("Where would you cache?");
        assertThatThrownBy(() -> DesignProblemBank.parse(VALID, "other.json")).hasMessageContaining("must be in toy.json");
        assertThatThrownBy(() -> DesignProblemBank.parse(VALID.replace("Where would you cache?", "Cache it."), "toy.json"))
                .hasMessageContaining("must be a question");
        assertThatThrownBy(() -> DesignProblemBank.parse(VALID.replace("\"CACHING\"", "\"CORRECTNESS\""), "toy.json"))
                .hasMessageContaining("not a dimension");
        assertThatThrownBy(() -> DesignProblemBank.parse(VALID.replace("\"levels\": [1, 2]", "\"levels\": [3, 2]"), "toy.json"))
                .hasMessageContaining("maxLevel");
        assertThatThrownBy(() -> DesignProblemBank.parse(VALID.replace("\"facts\"", "\"secret\": 1, \"facts\""), "toy.json"))
                .hasMessageContaining("secret");
        assertThatThrownBy(() -> new DesignProblemBank(List.of(DesignProblemBank.parse(VALID, "toy.json"), DesignProblemBank.parse(VALID, "toy.json"))))
                .hasMessageContaining("defined twice");
    }
}
