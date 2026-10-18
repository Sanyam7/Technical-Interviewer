package com.techinterviewer.coding.testing;

import com.techinterviewer.coding.domain.RunStatus;
import com.techinterviewer.coding.domain.TestOutcome;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.domain.TestStatus;
import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ExampleText;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.coding.sandbox.CodeSandbox;
import com.techinterviewer.coding.sandbox.SandboxResult;
import com.techinterviewer.coding.sandbox.SandboxUnavailableException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a solution against tests in the sandbox and grades it: the harness reports what the function returned, this
 * class compares it with the expected values, which never left the server.
 */
public final class TestRunner {

    /** Which tests to run. */
    public enum Scope {
        /** The visible examples: what "Run examples" does while the candidate works. */
        EXAMPLES,
        /** Every test: what a submitted solution is graded on. */
        ALL
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CodeSandbox sandbox;
    private final Harnesses harnesses;

    public TestRunner(CodeSandbox sandbox, Harnesses harnesses) {
        this.sandbox = sandbox;
        this.harnesses = harnesses;
    }

    /** Whether code in {@code language} can be run here. */
    public boolean canRun(CodeLanguage language) {
        return sandbox.enabled() && language.executable();
    }

    /**
     * @throws SandboxUnavailableException when execution is off, the language has no harness, or the sandbox fails to start
     */
    public TestRunReport run(CodingProblem problem, CodeLanguage language, String code, Scope scope) {
        if (!canRun(language)) {
            throw new SandboxUnavailableException(sandbox.enabled()
                    ? "Tests are not run for " + language.code() + "; the solution is reviewed by the AI interviewer only."
                    : "Code execution is turned off on this server.", false);
        }
        List<TestCase> tests = scope == Scope.EXAMPLES ? problem.examples() : problem.tests();
        String nonce = nonce();
        SandboxResult result = sandbox.run(harnesses.job(problem, language, code, tests, nonce));
        return grade(problem, language, tests, HarnessOutput.parse(result.stdout(), nonce), result);
    }

    static TestRunReport grade(CodingProblem problem, CodeLanguage language, List<TestCase> tests, HarnessOutput output,
            SandboxResult result) {
        if (output.compileError() != null) {
            List<TestOutcome> notRun = tests.stream()
                    .map(test -> outcome(test, TestStatus.NOT_RUN, null, "The code did not compile.", null))
                    .toList();
            return new TestRunReport(problem.id(), language.code(), RunStatus.COMPILATION_ERROR, notRun,
                    output.compileError().isBlank() ? "The code could not be compiled." : output.compileError(),
                    output.candidateOutput(), result.durationMs());
        }
        List<TestOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < tests.size(); i++) {
            TestCase test = tests.get(i);
            HarnessOutput.Raw raw = output.results().get(i);
            outcomes.add(raw == null ? outcome(test, TestStatus.NOT_RUN, null, notRunReason(result), null) : graded(problem, test, raw));
        }
        return new TestRunReport(problem.id(), language.code(), status(output, result), outcomes, null, output.candidateOutput(),
                result.durationMs());
    }

    private static TestOutcome graded(CodingProblem problem, TestCase test, HarnessOutput.Raw raw) {
        return switch (raw.status()) {
            case "OK" -> {
                JsonNode actual;
                try {
                    actual = JSON.readTree(raw.payload());
                } catch (JacksonException unreadable) {
                    yield outcome(test, TestStatus.ERROR, raw.millis(), "The returned value could not be read.", null);
                }
                boolean passed = ValueComparison.matches(actual, test.expected(), problem.comparison());
                yield outcome(test, passed ? TestStatus.PASSED : TestStatus.FAILED, raw.millis(),
                        passed ? null : "Wrong answer.", ExampleText.value(actual));
            }
            case "TIMEOUT" -> outcome(test, TestStatus.TIMEOUT, raw.millis(),
                    "Exceeded the time limit of " + problem.timeLimitMs() + " ms.", null);
            default -> outcome(test, TestStatus.ERROR, raw.millis(), errorText(raw.payload()), null);
        };
    }

    private static String errorText(String payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            return node.isString() ? node.asString() : payload;
        } catch (JacksonException unreadable) {
            return payload;
        }
    }

    private static RunStatus status(HarnessOutput output, SandboxResult result) {
        if (result.outputTruncated()) {
            return RunStatus.OUTPUT_LIMIT;
        }
        if (result.timedOut()) {
            return RunStatus.TIMED_OUT;
        }
        return output.finished() ? RunStatus.COMPLETED : RunStatus.CRASHED;
    }

    private static String notRunReason(SandboxResult result) {
        if (result.outputTruncated()) {
            return "Not run: the code printed more output than allowed.";
        }
        if (result.timedOut()) {
            return "Not run: the whole run exceeded its time limit.";
        }
        if (result.killedForMemory()) {
            return "Not run: the program ran out of memory.";
        }
        return "Not run: an earlier test did not finish.";
    }

    private static TestOutcome outcome(TestCase test, TestStatus status, Long millis, String message, String actual) {
        return new TestOutcome(test.id(), test.name(), test.category(), test.visible(), status, millis, message, actual);
    }

    private static String nonce() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "TI" + HexFormat.of().formatHex(bytes);
    }
}
