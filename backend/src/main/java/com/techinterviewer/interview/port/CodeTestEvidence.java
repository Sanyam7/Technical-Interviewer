package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the server knows about a coding solution's tests, for the evaluator (AI_ARCHITECTURE.md section 2.11). Trusted:
 * the server ran the code in the sandbox and compared the results itself.
 *
 * @param ran              the tests ran; false when they could not (the reason says why)
 * @param summary          one line, for example "7 of 9 functional tests passed; 1 of 3 edge cases failed"
 * @param failures         one line per test that did not pass: name, category, status, message, and for an example its
 *                         input and what the code returned
 * @param compileOutput    the compiler's message when the code did not compile
 * @param referenceTime    the time complexity of the reference solution, for example {@code O(n)}
 * @param referenceSpace   its space complexity
 */
public record CodeTestEvidence(boolean ran, String summary, List<String> failures, @Nullable String compileOutput,
        @Nullable String referenceTime, @Nullable String referenceSpace) {

    public static final int MAX_FAILURES = 20;

    public CodeTestEvidence {
        Require.notBlank(summary, "summary", 500);
        failures = Immutables.list(failures);
        Require.between(failures.size(), 0, MAX_FAILURES, "failures size");
        failures.forEach(line -> Require.notBlank(line, "failure", 1000));
        compileOutput = compileOutput == null ? null : Require.maxLength(compileOutput, "compileOutput", 4000);
    }

    /** No tests ran: the reason replaces the summary. */
    public static CodeTestEvidence notRun(String reason, @Nullable String referenceTime, @Nullable String referenceSpace) {
        return new CodeTestEvidence(false, reason, List.of(), null, referenceTime, referenceSpace);
    }
}
