package com.techinterviewer.coding.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProblemBankTest {

    private static final ProblemBank BANK = ProblemBank.load();

    @Test
    void hasAtLeastTwoProblemsAtEveryLevelTheEngineCanAskFor() {
        Map<Integer, Long> perLevel = BANK.all().stream().collect(Collectors.groupingBy(CodingProblem::difficulty, Collectors.counting()));

        assertThat(perLevel.keySet()).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        assertThat(perLevel.values()).allMatch(count -> count >= 2);
    }

    @Test
    void everyProblemHasExamplesEdgeCasesAndKeyPoints() {
        assertThat(BANK.all()).allSatisfy(problem -> {
            assertThat(problem.examples()).as(problem.id()).isNotEmpty();
            assertThat(problem.tests()).as(problem.id()).anyMatch(test -> test.category() == TestCategory.EDGE);
            assertThat(problem.keyPoints()).as(problem.id()).isNotEmpty();
            assertThat(problem.statement()).as(problem.id()).doesNotContain("Example");
        });
    }

    @Test
    void choosesAnUnusedProblemAtExactlyTheRequestedLevel() {
        CodingProblem first = BANK.choose(2, Set.of(), 7).orElseThrow();
        CodingProblem second = BANK.choose(2, Set.of(first.id()), 7).orElseThrow();

        assertThat(first.difficulty()).isEqualTo(2);
        assertThat(second.difficulty()).isEqualTo(2);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(BANK.choose(2, Set.of(first.id()), 7)).contains(second);
    }

    @Test
    void choosesNothingWhenEveryProblemOfTheLevelWasUsed() {
        Set<String> levelFive = BANK.all().stream().filter(problem -> problem.difficulty() == 5).map(CodingProblem::id).collect(Collectors.toSet());

        assertThat(BANK.choose(5, levelFive, 1)).isEmpty();
    }

    @Test
    void differentInterviewsGetDifferentProblemsAtTheSameLevel() {
        Set<String> chosen = Set.of(1L, 2L, 3L, 4L, 5L).stream()
                .map(seed -> BANK.choose(3, Set.of(), seed).orElseThrow().id())
                .collect(Collectors.toSet());

        assertThat(chosen).hasSizeGreaterThan(1);
    }

    @Test
    void expandsGeneratorsInInputsAndExpectedValues() {
        JsonMapper json = JsonMapper.builder().build();

        assertThat(ProblemParser.expand(json.readTree("{\"$range\": [1, 5]}")).toString()).isEqualTo("[1,2,3,4,5]");
        assertThat(ProblemParser.expand(json.readTree("{\"$range\": [5, 1, -2]}")).toString()).isEqualTo("[5,3,1]");
        assertThat(ProblemParser.expand(json.readTree("{\"$repeat\": [7, 3]}")).toString()).isEqualTo("[7,7,7]");
        assertThat(ProblemParser.expand(json.readTree("{\"$repeatString\": [\"ab\", 3]}")).asString()).isEqualTo("ababab");
        assertThat(ProblemParser.expand(json.readTree("{\"$concat\": [[1], {\"$repeat\": [0, 2]}, [9]]}")).toString()).isEqualTo("[1,0,0,9]");
        assertThat(ProblemParser.expand(json.readTree("{\"$concat\": [\"x\", {\"$repeatString\": [\"-\", 2]}]}")).asString()).isEqualTo("x--");
        assertThat(ProblemParser.expand(json.readTree("[1, 2]")).toString()).isEqualTo("[1,2]");
    }

    @Test
    void refusesARunawayGenerator() {
        JsonMapper json = JsonMapper.builder().build();

        assertThatThrownBy(() -> ProblemParser.expand(json.readTree("{\"$range\": [1, 1000000000]}")))
                .hasMessageContaining("at most");
        assertThatThrownBy(() -> ProblemParser.expand(json.readTree("{\"$range\": [5, 1]}")))
                .hasMessageContaining("empty or endless");
    }

    @Test
    void refusesAProblemWhoseTestDoesNotFitTheSignature() {
        String file = """
                {"id": "bad", "title": "Bad", "difficulty": 1, "statement": "s", "constraints": [],
                 "signature": {"name": "f", "params": [{"name": "n", "type": "int"}], "returns": "int"},
                 "comparison": "EXACT", "timeLimitMs": 1000, "complexity": {"time": "O(1)", "space": "O(1)"},
                 "keyPoints": [{"id": "kp1", "text": "k"}],
                 "tests": [
                   {"name": "e", "category": "EXAMPLE", "args": [1], "expected": 1},
                   {"name": "a", "category": "GENERAL", "args": ["x"], "expected": 1},
                   {"name": "b", "category": "GENERAL", "args": [2], "expected": 2},
                   {"name": "c", "category": "EDGE", "args": [3], "expected": 3}]}
                """;

        assertThatThrownBy(() -> ProblemParser.parse(file, "bad.json"))
                .hasMessageContaining("bad.json").hasMessageContaining("argument n is not a int");
    }

    @Test
    void refusesTestsOutOfCategoryOrderAndTooFewHiddenTests() {
        String outOfOrder = """
                {"id": "bad", "title": "Bad", "difficulty": 1, "statement": "s", "constraints": [],
                 "signature": {"name": "f", "params": [{"name": "n", "type": "int"}], "returns": "int"},
                 "comparison": "EXACT", "timeLimitMs": 1000, "complexity": {"time": "O(1)", "space": "O(1)"},
                 "keyPoints": [{"id": "kp1", "text": "k"}],
                 "tests": [
                   {"name": "e", "category": "EXAMPLE", "args": [1], "expected": 1},
                   {"name": "a", "category": "EDGE", "args": [1], "expected": 1},
                   {"name": "b", "category": "GENERAL", "args": [2], "expected": 2},
                   {"name": "c", "category": "GENERAL", "args": [3], "expected": 3}]}
                """;

        assertThatThrownBy(() -> ProblemParser.parse(outOfOrder, "bad.json")).hasMessageContaining("category order");
    }

    @Test
    void rendersStarterCodeForEveryLanguage() {
        Signature signature = BANK.find("two-sum").orElseThrow().signature();

        assertThat(StarterCode.render(signature, CodeLanguage.JAVA)).contains("class Solution").contains("public int[] twoSum(int[] nums, int target)");
        assertThat(StarterCode.render(signature, CodeLanguage.JAVASCRIPT)).contains("function twoSum(nums, target)").contains("@param {number[]} nums");
        assertThat(StarterCode.render(signature, CodeLanguage.TYPESCRIPT)).contains("function twoSum(nums: number[], target: number): number[]");
        assertThat(StarterCode.render(signature, CodeLanguage.PYTHON)).contains("def twoSum(self, nums: list[int], target: int) -> list[int]:");
        assertThat(StarterCode.render(signature, CodeLanguage.GO)).contains("func twoSum(nums []int, target int) []int");
    }

    @Test
    void writesExamplesTheWayPeopleReadThem() {
        CodingProblem problem = BANK.find("two-sum").orElseThrow();
        TestCase example = problem.examples().getFirst();

        assertThat(ExampleText.input(problem.signature(), example)).isEqualTo("nums = [2,7,11,15], target = 9");
        assertThat(ExampleText.value(example.expected())).isEqualTo("[0,1]");
    }

    @Test
    void knowsWhichLanguagesCanBeRun() {
        assertThat(CodeLanguage.fromCode("typescript")).contains(CodeLanguage.TYPESCRIPT);
        assertThat(CodeLanguage.JAVA.executable()).isTrue();
        assertThat(CodeLanguage.PYTHON.executable()).isFalse();
        assertThat(CodeLanguage.fromCode("cobol")).isEmpty();
    }
}
