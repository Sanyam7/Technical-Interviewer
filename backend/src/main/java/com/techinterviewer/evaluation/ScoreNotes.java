package com.techinterviewer.evaluation;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import org.jspecify.annotations.Nullable;

/**
 * Why a rating is what it is, when it is not simply the evaluator's: one sentence per adjusted criterion, written by the
 * server from the rule that applied and the evidence it used. Together with the evaluator's rationale it explains the
 * score of an answer.
 */
public final class ScoreNotes {

    private ScoreNotes() {
    }

    /**
     * @param score the stored criterion score
     * @param tests the test evidence of a coding answer, when its tests ran
     * @return the note, or null for a rating taken as the evaluator gave it
     */
    public static @Nullable String note(CriterionScore score, @Nullable TestEvidence tests) {
        if (score.measured()) {
            return measured(score, tests);
        }
        if (!score.capped()) {
            return null;
        }
        String lowered = "Lowered from " + score.modelRating() + " to " + score.rating() + ": ";
        RatingRules.Rule rule;
        try {
            rule = RatingRules.Rule.valueOf(score.cappedBy());
        } catch (IllegalArgumentException unknown) {
            return lowered + "a consistency rule applied.";
        }
        return lowered + switch (rule) {
            case FACTUAL_ERRORS -> score.criterion() == Criterion.CORRECTNESS
                    ? "the review lists factual errors in the answer, and a rating cannot claim it is fully correct."
                    : "the review lists a factual error about this part of the design.";
            case KEY_POINT_COVERAGE -> "the rating cannot exceed the share of expected points the answer actually covered.";
            case OFF_TOPIC -> "the answer did not address the question.";
            case INJECTION_EVIDENCE -> "the answer addressed the grader, so the rating is limited to what its own words show.";
            case DESIGN_EVIDENCE -> "the review could not point to anything you wrote about this, and a rating above 1 must rest on your own words.";
            case EDGE_CASE_TESTS -> tests == null ? "edge-case tests failed."
                    : tests.edgePassed() + " of " + tests.edgeTotal() + " edge-case tests passed.";
            case PERFORMANCE_TESTS -> "a performance test on a large input exceeded its time limit, so the solution is too slow for the constraints.";
            case NO_TEST_EVIDENCE -> "the code could not be run against tests, and unverified code rates at most 3 for correctness.";
            case TEST_RESULTS -> "the tests decided it.";
        };
    }

    private static String measured(CriterionScore score, @Nullable TestEvidence tests) {
        StringBuilder note = new StringBuilder("Set by the tests");
        if (tests != null) {
            note.append(tests.compiled()
                    ? ": " + tests.functionalPassed() + " of " + tests.functionalTotal() + " functional tests passed"
                    : ": the code did not compile, so no test passed");
        }
        note.append(". The reviewer's own rating was ").append(score.modelRating()).append('.');
        return note.toString();
    }
}
