package com.techinterviewer.coding.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.coding.References;
import com.techinterviewer.coding.domain.RunStatus;
import com.techinterviewer.coding.domain.TestOutcome;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.domain.TestStatus;
import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.problem.TestCategory;
import com.techinterviewer.coding.testing.Harnesses;
import com.techinterviewer.coding.testing.TestRunner;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The sandbox against a real Docker daemon: every reference solution passes every test of its problem in Java, JavaScript
 * and TypeScript, and hostile programs are contained (network, file system, processes, memory, time, output). Pulls the
 * two images when they are missing.
 */
@Tag("sandbox")
class CodeSandboxIT {

    private static final ProblemBank BANK = ProblemBank.load();
    private static final SandboxProperties PROPERTIES = Sandboxes.properties(true);
    private static final TestRunner RUNNER = new TestRunner(new DockerCodeSandbox(PROPERTIES), new Harnesses(PROPERTIES.images()));

    @BeforeAll
    static void pullImages() throws Exception {
        for (String image : List.of(PROPERTIES.images().java(), PROPERTIES.images().node())) {
            Process inspect = new ProcessBuilder(PROPERTIES.dockerCommand(), "image", "inspect", image).redirectErrorStream(true).start();
            inspect.getInputStream().transferTo(OutputStream.nullOutputStream());
            if (inspect.waitFor() != 0) {
                Process pull = new ProcessBuilder(PROPERTIES.dockerCommand(), "pull", image).redirectErrorStream(true).start();
                pull.getInputStream().transferTo(OutputStream.nullOutputStream());
                assertThat(pull.waitFor(10, TimeUnit.MINUTES)).as("pulling " + image).isTrue();
            }
        }
    }

    static Stream<Arguments> referenceSolutions() {
        return BANK.all().stream().flatMap(problem -> Stream.of(CodeLanguage.JAVA, CodeLanguage.JAVASCRIPT, CodeLanguage.TYPESCRIPT)
                .map(language -> Arguments.of(problem.id(), language)));
    }

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("referenceSolutions")
    void everyReferenceSolutionPassesEveryTest(String problemId, CodeLanguage language) {
        CodingProblem problem = BANK.find(problemId).orElseThrow();

        TestRunReport report = RUNNER.run(problem, language, References.solution(problemId, language), TestRunner.Scope.ALL);

        assertThat(report.status()).as(report.toString()).isEqualTo(RunStatus.COMPLETED);
        assertThat(report.outcomes()).as(report.toString()).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(TestStatus.PASSED));
        assertThat(report.total()).isEqualTo(problem.tests().size());
    }

    @Test
    void runsOnlyTheExamplesWhenAskedTo() {
        CodingProblem problem = BANK.find("two-sum").orElseThrow();

        TestRunReport report = RUNNER.run(problem, CodeLanguage.JAVASCRIPT, References.solution("two-sum", CodeLanguage.JAVASCRIPT),
                TestRunner.Scope.EXAMPLES);

        assertThat(report.outcomes()).hasSize(problem.examples().size()).allMatch(TestOutcome::visible);
    }

    @Test
    void gradesAWrongAnswerAsFailedWithWhatItReturned() {
        CodingProblem problem = BANK.find("maximum-subarray").orElseThrow();
        String startsFromZero = "function maxSubArray(nums) { let best = 0, cur = 0; for (const x of nums) { cur = Math.max(0, cur + x); "
                + "best = Math.max(best, cur); } return best; }";

        TestRunReport report = RUNNER.run(problem, CodeLanguage.JAVASCRIPT, startsFromZero, TestRunner.Scope.ALL);

        TestOutcome allNegative = outcome(report, "all negative");
        assertThat(allNegative.status()).isEqualTo(TestStatus.FAILED);
        assertThat(allNegative.actual()).isEqualTo("0");
        assertThat(outcome(report, "Example 1").status()).isEqualTo(TestStatus.PASSED);
    }

    @Test
    void reportsACompilationErrorForEachLanguage() {
        CodingProblem problem = BANK.find("two-sum").orElseThrow();

        TestRunReport java = RUNNER.run(problem, CodeLanguage.JAVA, "class Solution { public int[] twoSum(int[] nums, int target) { return nums } }",
                TestRunner.Scope.EXAMPLES);
        TestRunReport javascript = RUNNER.run(problem, CodeLanguage.JAVASCRIPT, "function twoSum(nums, target) { return [ }", TestRunner.Scope.EXAMPLES);
        TestRunReport missing = RUNNER.run(problem, CodeLanguage.TYPESCRIPT, "function addTwo(a: number): number { return a + 2; }",
                TestRunner.Scope.EXAMPLES);

        assertThat(java.status()).isEqualTo(RunStatus.COMPILATION_ERROR);
        assertThat(java.compileOutput()).contains("Solution.java").contains("';' expected");
        assertThat(javascript.status()).isEqualTo(RunStatus.COMPILATION_ERROR);
        assertThat(javascript.compileOutput()).contains("SyntaxError");
        assertThat(missing.status()).isEqualTo(RunStatus.COMPILATION_ERROR);
        assertThat(missing.compileOutput()).contains("function twoSum is not defined");
        assertThat(java.outcomes()).allMatch(outcome -> outcome.status() == TestStatus.NOT_RUN);
    }

    @Test
    void reportsAnExceptionWithItsLine() {
        CodingProblem problem = BANK.find("two-sum").orElseThrow();
        String throwing = "class Solution {\n    public int[] twoSum(int[] nums, int target) {\n        return new int[] {nums[nums.length]};\n    }\n}\n";

        TestRunReport report = RUNNER.run(problem, CodeLanguage.JAVA, throwing, TestRunner.Scope.EXAMPLES);

        assertThat(report.outcomes().getFirst().status()).isEqualTo(TestStatus.ERROR);
        assertThat(report.outcomes().getFirst().message()).contains("ArrayIndexOutOfBoundsException").contains("line 3");
    }

    @Test
    void anEndlessLoopTimesOutInsteadOfHangingTheRun() {
        CodingProblem problem = BANK.find("two-sum").orElseThrow();

        TestRunReport javascript = RUNNER.run(problem, CodeLanguage.JAVASCRIPT,
                "function twoSum(nums, target) { if (nums.length === 4) { while (true) {} } return [1, 2]; }", TestRunner.Scope.EXAMPLES);
        TestRunReport java = RUNNER.run(problem, CodeLanguage.JAVA,
                "class Solution { public int[] twoSum(int[] nums, int target) { while (true) { } } }", TestRunner.Scope.EXAMPLES);

        // Node interrupts the test and goes on with the next one; Java cannot stop a thread, so it ends the run.
        assertThat(javascript.outcomes().get(0).status()).isEqualTo(TestStatus.TIMEOUT);
        assertThat(javascript.outcomes().get(1).status()).isEqualTo(TestStatus.PASSED);
        assertThat(java.outcomes().get(0).status()).isEqualTo(TestStatus.TIMEOUT);
        assertThat(java.outcomes().get(1).status()).isEqualTo(TestStatus.NOT_RUN);
    }

    @Test
    void aSlowSolutionTimesOutOnThePerformanceTestOnly() {
        CodingProblem problem = BANK.find("two-sum").orElseThrow();
        String quadratic = "function twoSum(nums, target) { for (let i = 0; i < nums.length; i++) for (let j = i + 1; j < nums.length; j++) "
                + "if (nums[i] + nums[j] === target) return [i, j]; return []; }";

        TestRunReport report = RUNNER.run(problem, CodeLanguage.JAVASCRIPT, quadratic, TestRunner.Scope.ALL);

        assertThat(report.performanceTimedOut()).as(report.toString()).isTrue();
        assertThat(report.functionalPassed()).isEqualTo(report.functionalTotal());
    }

    @Test
    void theContainerHasNoNetwork() {
        String code = "import java.net.*;\nclass Solution { public int[] twoSum(int[] nums, int target) {\n"
                + "  try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(\"1.1.1.1\", 53), 2000); return new int[] {1}; }\n"
                + "  catch (Exception e) { throw new IllegalStateException(\"no network: \" + e.getClass().getSimpleName()); } } }";

        TestOutcome outcome = RUNNER.run(BANK.find("two-sum").orElseThrow(), CodeLanguage.JAVA, code, TestRunner.Scope.EXAMPLES).outcomes().getFirst();

        assertThat(outcome.status()).isEqualTo(TestStatus.ERROR);
        assertThat(outcome.message()).contains("no network");
    }

    @Test
    void theFileSystemIsReadOnlyOutsideTmpAndTheUserIsUnprivileged() {
        String code = "import java.nio.file.*;\nclass Solution { public int[] twoSum(int[] nums, int target) {\n"
                + "  String user = System.getProperty(\"user.name\");\n"
                + "  try { Files.writeString(Path.of(\"/etc/owned\"), \"x\"); return new int[] {-1}; }\n"
                + "  catch (Exception e) { throw new IllegalStateException(\"read-only as \" + user + \": \" + e.getClass().getSimpleName()); } } }";

        TestOutcome outcome = RUNNER.run(BANK.find("two-sum").orElseThrow(), CodeLanguage.JAVA, code, TestRunner.Scope.EXAMPLES).outcomes().getFirst();

        assertThat(outcome.message()).contains("read-only as ").doesNotContain("as root");
    }

    @Test
    void theExpectedOutputsNeverEnterTheContainer() {
        // The inputs the harness reads carry the arguments of each test and nothing else.
        String code = "import java.nio.file.*;\nclass Solution { public int[] twoSum(int[] nums, int target) {\n"
                + "  try { String input = Files.readString(Path.of(\"input.json\"));\n"
                + "    if (input.contains(\"expected\") || input.contains(\"[0,1]\")) throw new IllegalStateException(\"leak: \" + input); }\n"
                + "  catch (java.io.IOException e) { throw new RuntimeException(e); }\n"
                + "  return new int[] {0, 1}; } }";

        TestRunReport report = RUNNER.run(BANK.find("two-sum").orElseThrow(), CodeLanguage.JAVA, code, TestRunner.Scope.EXAMPLES);

        assertThat(report.outcomes().getFirst().status()).as(report.toString()).isEqualTo(TestStatus.PASSED);
    }

    @Test
    void aForkBombIsStoppedByTheProcessLimit() {
        String code = "const { execSync } = (() => { try { return require('node:child_process'); } catch (e) { return {}; } })();\n"
                + "function twoSum(nums, target) { return [typeof require, typeof process].includes('function') ? [9, 9] : [0, 1]; }";

        TestRunReport report = RUNNER.run(BANK.find("two-sum").orElseThrow(), CodeLanguage.JAVASCRIPT, code, TestRunner.Scope.EXAMPLES);

        // Inside the harness's context the code has neither require nor process, so it cannot even start a child process.
        assertThat(report.outcomes().getFirst().status()).isEqualTo(TestStatus.PASSED);
        assertThat(report.outcomes().getFirst().actual()).isEqualTo("[0,1]");

        String threads = "import java.util.*;\nclass Solution { public int[] twoSum(int[] nums, int target) {\n"
                + "  List<Thread> all = new ArrayList<>();\n"
                + "  try { for (int i = 0; i < 10_000; i++) { Thread t = new Thread(() -> { try { Thread.sleep(60_000); } catch (InterruptedException e) { } });"
                + " t.setDaemon(true); t.start(); all.add(t); } }\n"
                + "  catch (Throwable e) { throw new IllegalStateException(\"limited after \" + all.size() + \" threads\"); }\n"
                + "  return new int[] {all.size()}; } }";

        TestOutcome limited = RUNNER.run(BANK.find("two-sum").orElseThrow(), CodeLanguage.JAVA, threads, TestRunner.Scope.EXAMPLES).outcomes().getFirst();

        assertThat(limited.status()).isEqualTo(TestStatus.ERROR);
        assertThat(limited.message()).contains("limited after");
    }

    @Test
    void aMemoryHogCannotTakeTheHostsMemory() {
        String code = "import java.util.*;\nclass Solution { public int[] twoSum(int[] nums, int target) {\n"
                + "  List<long[]> hog = new ArrayList<>(); while (true) { hog.add(new long[1 << 20]); } } }";

        TestRunReport report = RUNNER.run(BANK.find("two-sum").orElseThrow(), CodeLanguage.JAVA, code, TestRunner.Scope.EXAMPLES);

        assertThat(report.outcomes().getFirst().status()).isEqualTo(TestStatus.ERROR);
        assertThat(report.outcomes().getFirst().message()).contains("OutOfMemoryError");
    }

    @Test
    void floodingTheOutputEndsTheRun() {
        TestRunner small = new TestRunner(new DockerCodeSandbox(Sandboxes.properties(true, Duration.ofSeconds(30), 16_384)),
                new Harnesses(PROPERTIES.images()));
        String code = "function twoSum(nums, target) { for (;;) console.log('spam spam spam spam spam spam spam spam'); }";

        TestRunReport report = small.run(BANK.find("two-sum").orElseThrow(), CodeLanguage.JAVASCRIPT, code, TestRunner.Scope.EXAMPLES);

        assertThat(report.status()).isIn(RunStatus.OUTPUT_LIMIT, RunStatus.COMPLETED);
        assertThat(report.candidateOutput().length()).isLessThanOrEqualTo(TestRunReport.MAX_CANDIDATE_OUTPUT);
    }

    @Test
    void theWholeRunHasAWallClockLimitAndTheContainerIsRemoved() throws Exception {
        TestRunner quick = new TestRunner(new DockerCodeSandbox(Sandboxes.properties(true, Duration.ofSeconds(6), 262_144)),
                new Harnesses(PROPERTIES.images()));
        // A top-level loop that never yields: Node's per-script timeout interrupts it, so block on a busy native call instead.
        String code = "const end = Date.now() + 60000; while (Date.now() < end) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1000); }\n"
                + "function twoSum(nums, target) { return [0, 1]; }";

        long start = System.nanoTime();
        TestRunReport report = quick.run(BANK.find("two-sum").orElseThrow(), CodeLanguage.JAVASCRIPT, code, TestRunner.Scope.EXAMPLES);
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertThat(report.status()).isIn(RunStatus.TIMED_OUT, RunStatus.COMPILATION_ERROR);
        assertThat(seconds).isLessThan(20);
        Process list = new ProcessBuilder(PROPERTIES.dockerCommand(), "ps", "-a", "--filter", "label=app=tech-interviewer-sandbox", "-q")
                .redirectErrorStream(true).start();
        String remaining = new String(list.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        list.waitFor();
        // Let --rm finish removing containers of the runs above.
        for (int attempt = 0; attempt < 20 && !remaining.isEmpty(); attempt++) {
            Thread.sleep(500);
            Process again = new ProcessBuilder(PROPERTIES.dockerCommand(), "ps", "-a", "--filter", "label=app=tech-interviewer-sandbox", "-q")
                    .redirectErrorStream(true).start();
            remaining = new String(again.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            again.waitFor();
        }
        assertThat(remaining).as("sandbox containers left behind").isEmpty();
    }

    @Test
    void reportsTheCategoriesSoEdgeCasesCanBeScored() {
        CodingProblem problem = BANK.find("maximum-subarray").orElseThrow();

        TestRunReport report = RUNNER.run(problem, CodeLanguage.TYPESCRIPT, References.solution("maximum-subarray", CodeLanguage.TYPESCRIPT),
                TestRunner.Scope.ALL);

        assertThat(report.total(TestCategory.EDGE)).isGreaterThan(0);
        assertThat(report.passed(TestCategory.EDGE)).isEqualTo(report.total(TestCategory.EDGE));
    }

    private static TestOutcome outcome(TestRunReport report, String name) {
        return report.outcomes().stream().filter(outcome -> outcome.name().equals(name)).findFirst().orElseThrow();
    }
}
