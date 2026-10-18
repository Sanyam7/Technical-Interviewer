package com.techinterviewer.evaluation;

import com.techinterviewer.common.validation.Require;
import org.jspecify.annotations.Nullable;

/**
 * What running a coding answer against its problem's tests showed, as the scoring rules need it (AI_ARCHITECTURE.md
 * section 2.11). The tests, not the model, decide correctness: the server compared what the code returned with expected
 * values the sandbox never saw.
 *
 * @param compiled            the code compiled (or loaded) and the tests ran
 * @param functionalPassed    passed tests that decide correctness (examples, general and edge cases)
 * @param functionalTotal     tests that decide correctness
 * @param edgePassed          passed edge-case tests
 * @param edgeTotal           edge-case tests
 * @param performanceTimedOut a performance test exceeded its time limit
 */
public record TestEvidence(boolean compiled, int functionalPassed, int functionalTotal, int edgePassed, int edgeTotal,
        boolean performanceTimedOut) {

    public TestEvidence {
        Require.atLeast(functionalTotal, 1, "functionalTotal");
        Require.between(functionalPassed, 0, functionalTotal, "functionalPassed");
        Require.atLeast(edgeTotal, 0, "edgeTotal");
        Require.between(edgePassed, 0, edgeTotal, "edgePassed");
        if (!compiled && (functionalPassed > 0 || edgePassed > 0)) {
            throw new IllegalArgumentException("code that did not compile passed no test");
        }
    }

    /**
     * CORRECTNESS from the share of functional tests passed: all of them 4, at least 80 % 3, at least half 2, any 1, none
     * (or no compilation) 0.
     */
    public int correctnessRating() {
        if (!compiled || functionalPassed == 0) {
            return 0;
        }
        if (functionalPassed == functionalTotal) {
            return 4;
        }
        // Integer arithmetic: passed / total >= 0.8 is 5 * passed >= 4 * total.
        if (5 * functionalPassed >= 4 * functionalTotal) {
            return 3;
        }
        return 2 * functionalPassed >= functionalTotal ? 2 : 1;
    }

    /** The highest EDGE_CASES rating the edge-case tests support, {@code ceil(4 * passed / total)}; null without edge-case tests. */
    public @Nullable Integer edgeCaseCap() {
        return edgeTotal == 0 ? null : Math.ceilDiv(4 * edgePassed, edgeTotal);
    }
}
