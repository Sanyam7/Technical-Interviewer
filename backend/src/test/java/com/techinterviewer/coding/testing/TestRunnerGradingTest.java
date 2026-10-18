package com.techinterviewer.coding.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.coding.domain.RunStatus;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.domain.TestStatus;
import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.Comparison;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.coding.sandbox.CodeSandbox;
import com.techinterviewer.coding.sandbox.SandboxJob;
import com.techinterviewer.coding.sandbox.SandboxResult;
import com.techinterviewer.coding.sandbox.SandboxUnavailableException;
import com.techinterviewer.coding.sandbox.Sandboxes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The grading of what a harness printed, with a scripted sandbox (no Docker). */
class TestRunnerGradingTest {

    private static final CodingProblem TWO_SUM = ProblemBank.load().find("two-sum").orElseThrow();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A sandbox that answers with a fixed output, after replacing NONCE with the job's nonce. */
    private static final class Scripted implements CodeSandbox {

        private final String output;
        private final boolean timedOut;
        final AtomicReference<SandboxJob> job = new AtomicReference<>();

        Scripted(String output, boolean timedOut) {
            this.output = output;
            this.timedOut = timedOut;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public SandboxResult run(SandboxJob given) {
            job.set(given);
            return new SandboxResult(timedOut ? -1 : 0, output.replace("NONCE", given.nonce()), "", timedOut, false, 900);
        }
    }

    private static TestRunReport examples(String output, boolean timedOut) {
        Scripted sandbox = new Scripted(output, timedOut);
        return new TestRunner(sandbox, new Harnesses(Sandboxes.properties(true).images()))
                .run(TWO_SUM, CodeLanguage.JAVASCRIPT, "code", TestRunner.Scope.EXAMPLES);
    }

    @Test
    void comparesWhatTheFunctionReturnedWithTheExpectedValue() {
        TestRunReport report = examples("NONCE RESULT 0 OK 3 [0,1]\nNONCE RESULT 1 OK 2 [2,1]\nNONCE DONE\n", false);

        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(report.outcomes().get(0).status()).isEqualTo(TestStatus.PASSED);
        assertThat(report.outcomes().get(1).status()).isEqualTo(TestStatus.FAILED);
        assertThat(report.outcomes().get(1).actual()).isEqualTo("[2,1]");
        assertThat(report.outcomes().get(0).timeMs()).isEqualTo(3);
    }

    @Test
    void keepsTheCandidatesOwnOutputApartFromTheHarnessLines() {
        TestRunReport report = examples("debug 1\npartial lineNONCE RESULT 0 OK 1 [0,1]\nNONCE RESULT 1 OK 1 [1,2]\nNONCE DONE\n", false);

        assertThat(report.candidateOutput()).isEqualTo("debug 1\npartial line");
        assertThat(report.passed()).isEqualTo(2);
    }

    @Test
    void ignoresLinesWithoutTheNonceAndKeepsTheFirstReportOfATest() {
        TestRunReport report = examples("TI0000 RESULT 0 OK 1 [0,1]\nNONCE RESULT 0 OK 1 [5,5]\nNONCE RESULT 0 OK 1 [0,1]\n"
                + "NONCE RESULT 1 OK 1 [1,2]\nNONCE DONE\n", false);

        assertThat(report.outcomes().get(0).status()).isEqualTo(TestStatus.FAILED);
    }

    @Test
    void reportsErrorsAndTimeouts() {
        TestRunReport report = examples("NONCE RESULT 0 ERROR 4 \"TypeError: x is undefined (line 2)\"\nNONCE RESULT 1 TIMEOUT 2000 null\nNONCE DONE\n",
                false);

        assertThat(report.outcomes().get(0).status()).isEqualTo(TestStatus.ERROR);
        assertThat(report.outcomes().get(0).message()).isEqualTo("TypeError: x is undefined (line 2)");
        assertThat(report.outcomes().get(1).status()).isEqualTo(TestStatus.TIMEOUT);
        assertThat(report.outcomes().get(1).message()).contains("2000 ms");
    }

    @Test
    void marksTestsThatWereNotReachedWhenTheRunTimedOut() {
        TestRunReport report = examples("NONCE RESULT 0 OK 1 [0,1]\n", true);

        assertThat(report.status()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(report.outcomes().get(1).status()).isEqualTo(TestStatus.NOT_RUN);
        assertThat(report.outcomes().get(1).message()).contains("time limit");
    }

    @Test
    void calledARunThatEndedWithoutFinishingCrashed() {
        assertThat(examples("NONCE RESULT 0 OK 1 [0,1]\n", false).status()).isEqualTo(RunStatus.CRASHED);
    }

    @Test
    void reportsACompilationError() {
        TestRunReport report = examples("NONCE COMPILE_ERROR\nSolution.java:3: error: ';' expected\n", false);

        assertThat(report.status()).isEqualTo(RunStatus.COMPILATION_ERROR);
        assertThat(report.compileOutput()).isEqualTo("Solution.java:3: error: ';' expected");
        assertThat(report.outcomes()).allMatch(outcome -> outcome.status() == TestStatus.NOT_RUN);
    }

    @Test
    void anUnreadableValueIsAnError() {
        TestRunReport report = examples("NONCE RESULT 0 OK 1 [0,\nNONCE RESULT 1 OK 1 [1,2]\nNONCE DONE\n", false);

        assertThat(report.outcomes().get(0).status()).isEqualTo(TestStatus.ERROR);
    }

    @Test
    void sendsTheInputsButNeverTheExpectedValues() {
        Scripted sandbox = new Scripted("NONCE DONE\n", false);
        new TestRunner(sandbox, new Harnesses(Sandboxes.properties(true).images())).run(TWO_SUM, CodeLanguage.JAVA,
                "class Solution {}", TestRunner.Scope.ALL);

        SandboxJob job = sandbox.job.get();
        String input = job.files().stream().filter(file -> file.name().equals("input.json")).findFirst()
                .map(file -> new String(file.content(), StandardCharsets.UTF_8)).orElseThrow();
        assertThat(input).doesNotContain("expected");
        assertThat(JSON.readTree(input).size()).isEqualTo(TWO_SUM.tests().size());
        assertThat(job.files()).extracting(SandboxJob.SandboxFile::name).containsExactly("Solution.java", "Main.java", "input.json");
        assertThat(job.image()).isEqualTo("eclipse-temurin:21-jdk-alpine");
    }

    @Test
    void comparesNumbersByValueAndUnorderedArraysAsMultisets() {
        assertThat(ValueComparison.matches(JSON.readTree("[1,2.0]"), JSON.readTree("[1,2]"), Comparison.EXACT)).isTrue();
        assertThat(ValueComparison.matches(JSON.readTree("[2,1]"), JSON.readTree("[1,2]"), Comparison.EXACT)).isFalse();
        assertThat(ValueComparison.matches(JSON.readTree("[2,1,1]"), JSON.readTree("[1,2,1]"), Comparison.UNORDERED)).isTrue();
        assertThat(ValueComparison.matches(JSON.readTree("[2,1]"), JSON.readTree("[1,2,1]"), Comparison.UNORDERED)).isFalse();
        assertThat(ValueComparison.matches(JSON.readTree("\"a\""), JSON.readTree("\"a\""), Comparison.EXACT)).isTrue();
        assertThat(ValueComparison.matches(JSON.readTree("true"), JSON.readTree("1"), Comparison.EXACT)).isFalse();
    }

    @Test
    void refusesToRunALanguageWithoutHarnessOrWhenExecutionIsOff() {
        TestRunner off = new TestRunner(CodeSandbox.DISABLED, new Harnesses(Sandboxes.properties(false).images()));
        TestRunner on = new TestRunner(new Scripted("", false), new Harnesses(Sandboxes.properties(true).images()));

        assertThatThrownBy(() -> off.run(TWO_SUM, CodeLanguage.JAVA, "x", TestRunner.Scope.ALL))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("turned off");
        assertThatThrownBy(() -> on.run(TWO_SUM, CodeLanguage.PYTHON, "x", TestRunner.Scope.ALL))
                .isInstanceOf(SandboxUnavailableException.class).hasMessageContaining("python");
        assertThat(on.canRun(CodeLanguage.TYPESCRIPT)).isTrue();
        assertThat(off.canRun(CodeLanguage.TYPESCRIPT)).isFalse();
    }

    @Test
    void generatesTheJavaCallFromTheSignature() {
        assertThat(Harnesses.javaCall(TWO_SUM.signature())).isEqualTo("return new Solution().twoSum(toIntArray(a.get(0)), toInt(a.get(1)));");
        List<TestCase> examples = TWO_SUM.examples();
        assertThat(new String(Harnesses.input(examples), StandardCharsets.UTF_8)).isEqualTo("[{\"args\":[[2,7,11,15],9]},{\"args\":[[3,2,4],6]}]");
    }
}
