package com.techinterviewer.coding.support;

import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.coding.problem.TestCategory;
import com.techinterviewer.coding.sandbox.CodeSandbox;
import com.techinterviewer.coding.sandbox.SandboxJob;
import com.techinterviewer.coding.sandbox.SandboxResult;
import com.techinterviewer.coding.sandbox.SandboxUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A sandbox for integration tests (no Docker): it answers like the real harness, from the inputs the job carries. It finds
 * the problem by its tests' inputs and returns the expected values, so a solution passes, unless the code carries a marker:
 * {@code COMPILE_ERROR} fails to compile, {@code WRONG_EDGES} returns a wrong value for the edge-case tests, {@code SLOW}
 * times out on the performance tests. Lives under src/test only.
 */
public final class ScriptedSandbox implements CodeSandbox {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProblemBank bank;
    private final List<SandboxJob> jobs = new CopyOnWriteArrayList<>();
    private volatile boolean enabled = true;
    private volatile boolean breakNext;

    public ScriptedSandbox(ProblemBank bank) {
        this.bank = bank;
    }

    public void enabled(boolean on) {
        this.enabled = on;
    }

    public List<SandboxJob> jobs() {
        return List.copyOf(jobs);
    }

    public void reset() {
        jobs.clear();
        enabled = true;
        breakNext = false;
    }

    /** The next run fails with an unexpected exception, as a broken sandbox would. */
    public void breakNextRun() {
        breakNext = true;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public SandboxResult run(SandboxJob job) {
        if (!enabled) {
            throw new SandboxUnavailableException("Code execution is turned off on this server.", false);
        }
        jobs.add(job);
        if (breakNext) {
            breakNext = false;
            throw new IllegalStateException("the sandbox broke");
        }
        String code = text(job, job.files().getFirst().name());
        JsonNode input = JSON.readTree(text(job, "input.json"));
        String nonce = job.nonce();
        if (code.contains("COMPILE_ERROR")) {
            return result(nonce + " COMPILE_ERROR\nSolution.java:1: error: ';' expected\n");
        }
        List<TestCase> tests = testsFor(input);
        StringBuilder out = new StringBuilder("debug output from the candidate\n");
        for (int i = 0; i < tests.size(); i++) {
            TestCase test = tests.get(i);
            if (code.contains("SLOW") && test.category() == TestCategory.PERFORMANCE) {
                out.append(nonce).append(" RESULT ").append(i).append(" TIMEOUT 2000 null\n");
            } else if (code.contains("WRONG_EDGES") && test.category() == TestCategory.EDGE) {
                out.append(nonce).append(" RESULT ").append(i).append(" OK 1 -12345\n");
            } else {
                out.append(nonce).append(" RESULT ").append(i).append(" OK 1 ").append(test.expected()).append('\n');
            }
        }
        return result(out.append(nonce).append(" DONE\n").toString());
    }

    /** The tests whose inputs are the job's inputs, in order. */
    private List<TestCase> testsFor(JsonNode input) {
        for (CodingProblem problem : bank.all()) {
            for (List<TestCase> candidate : List.of(problem.tests(), problem.examples())) {
                if (candidate.size() == input.size() && sameInputs(candidate, input)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("no problem has these inputs");
    }

    private static boolean sameInputs(List<TestCase> tests, JsonNode input) {
        List<JsonNode> given = new ArrayList<>();
        input.forEach(entry -> given.add(entry.get("args")));
        for (int i = 0; i < tests.size(); i++) {
            // Compared as text: a generated input holds long nodes, the same value read back from JSON int nodes.
            if (!JSON.writeValueAsString(tests.get(i).arguments()).equals(given.get(i).toString())) {
                return false;
            }
        }
        return true;
    }

    private static String text(SandboxJob job, String name) {
        return job.files().stream().filter(file -> file.name().equals(name)).findFirst()
                .map(file -> new String(file.content(), StandardCharsets.UTF_8)).orElseThrow();
    }

    private static SandboxResult result(String stdout) {
        return new SandboxResult(0, stdout, "", false, false, 700);
    }
}
