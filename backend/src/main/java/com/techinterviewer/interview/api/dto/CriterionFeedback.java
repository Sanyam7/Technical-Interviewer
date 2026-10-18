package com.techinterviewer.interview.api.dto;

import com.techinterviewer.evaluation.domain.Criterion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "How one criterion of the rubric was rated, and on what basis.")
public record CriterionFeedback(
        Criterion criterion,
        @Schema(description = "0 (absent or wrong) to 4 (expert): the rating the score was computed from.", minimum = "0", maximum = "4") int rating,
        @Schema(description = "The evaluator's reason.") String rationale,
        @Schema(description = "MODEL: the evaluator's rating as given. TESTS: decided by the test results (the correctness of a tested "
                + "solution). CAPPED: lowered by a rule because it contradicted the evaluator's own findings or the tests.",
                allowableValues = {"MODEL", "TESTS", "CAPPED"}) String basis,
        @Schema(description = "The evaluator's own rating, when the tests decided or a rule lowered it.", nullable = true) @Nullable Integer modelRating,
        @Schema(description = "Why the rating differs from the evaluator's, written by the server; absent for a rating taken as given.",
                nullable = true) @Nullable String note,
        @Schema(description = "The candidate's own words the rating rests on, as quoted by the evaluator and found by the server in the "
                + "candidate's answers or diagram (system design dimensions; empty otherwise).") List<String> evidence) {

    public CriterionFeedback {
        evidence = List.copyOf(evidence);
    }

    /** A rating without quoted evidence. */
    public CriterionFeedback(Criterion criterion, int rating, String rationale, String basis, @Nullable Integer modelRating,
            @Nullable String note) {
        this(criterion, rating, rationale, basis, modelRating, note, List.of());
    }

    /** A rating taken as the evaluator gave it. */
    public CriterionFeedback(Criterion criterion, int rating, String rationale) {
        this(criterion, rating, rationale, "MODEL", null, null);
    }
}
