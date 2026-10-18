package com.techinterviewer.evaluation.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One rated criterion of an evaluation, stored in {@code evaluations.criterion_scores}. The rating comes from the
 * evaluator (0 absent or wrong to 4 expert); the weight is applied by the server's scoring policy, never by the model.
 *
 * <p>When the server lowered the model's rating because it contradicted the model's own verified findings or the test
 * results ({@code RatingRules}), {@code rating} is the rating that was scored, {@code modelRating} the one the model gave
 * and {@code cappedBy} the rule that lowered it. When a measurement decided the rating instead of the model (the tests of a
 * coding answer decide its correctness), {@code setBy} names it and {@code modelRating} keeps what the model said, which may
 * be lower, equal or higher. All three are absent for a rating taken as given (and in rows written before the rules
 * existed).
 *
 * @param criterion   the dimension
 * @param rating      0 to 4, the rating the score is computed from
 * @param weight      share of the overall score, above 0 up to 1
 * @param rationale   the evaluator's short justification (may be empty)
 * @param modelRating the model's own rating when it was lowered, else null
 * @param cappedBy    the name of the rule that lowered it, else null
 * @param setBy       the measurement that decided the rating instead of the model ({@code TEST_RESULTS}), else null
 * @param evidence    the candidate's own words the rating rests on, as the evaluator quoted them and the server found them in the
 *                    candidate's text (system design: at most {@value #MAX_EVIDENCE} per dimension); empty for other evaluators
 */
public record CriterionScore(
        Criterion criterion,
        int rating,
        double weight,
        String rationale,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Integer modelRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String cappedBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String setBy,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> evidence) {

    public static final int MIN_RATING = 0;
    public static final int MAX_RATING = 4;
    public static final int MAX_RATIONALE_LENGTH = 2000;
    public static final int MAX_EVIDENCE = 2;
    public static final int MAX_EVIDENCE_LENGTH = 300;

    public CriterionScore {
        Require.nonNull(criterion, "criterion");
        Require.between(rating, MIN_RATING, MAX_RATING, "rating");
        if (!(weight > 0 && weight <= 1)) {
            throw new IllegalArgumentException("weight must be above 0 and at most 1 but was " + weight);
        }
        Require.text(rationale, "rationale", MAX_RATIONALE_LENGTH);
        if (cappedBy != null && setBy != null) {
            throw new IllegalArgumentException("a rating is either lowered by a rule or set by a measurement, not both");
        }
        if ((modelRating == null) != (cappedBy == null && setBy == null)) {
            throw new IllegalArgumentException("an adjusted rating names both the model's rating and the rule or measurement, or neither");
        }
        if (modelRating != null) {
            Require.between(modelRating, MIN_RATING, MAX_RATING, "modelRating");
        }
        if (cappedBy != null && modelRating <= rating) {
            throw new IllegalArgumentException("a rule only lowers a rating: the model's " + modelRating + " is not above " + rating);
        }
        Require.nullOrNotBlank(cappedBy, "cappedBy", 40);
        Require.nullOrNotBlank(setBy, "setBy", 40);
        evidence = Immutables.list(evidence);
        if (evidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException("a criterion keeps at most " + MAX_EVIDENCE + " quotes");
        }
        evidence.forEach(quote -> Require.notBlank(quote, "evidence quote", MAX_EVIDENCE_LENGTH));
    }

    /** A rating without quoted evidence. */
    public CriterionScore(Criterion criterion, int rating, double weight, String rationale, @Nullable Integer modelRating,
            @Nullable String cappedBy, @Nullable String setBy) {
        this(criterion, rating, weight, rationale, modelRating, cappedBy, setBy, List.of());
    }

    /** A rating scored as the model gave it. */
    public CriterionScore(Criterion criterion, int rating, double weight, String rationale) {
        this(criterion, rating, weight, rationale, null, null, null);
    }

    /** A rating lowered by a rule, or taken as given when both are null. */
    public CriterionScore(Criterion criterion, int rating, double weight, String rationale, @Nullable Integer modelRating,
            @Nullable String cappedBy) {
        this(criterion, rating, weight, rationale, modelRating, cappedBy, null);
    }

    /** A rating a measurement decided; the model's own rating is kept for comparison. */
    public static CriterionScore measured(Criterion criterion, int rating, double weight, String rationale, int modelRating, String setBy) {
        return new CriterionScore(criterion, rating, weight, rationale, modelRating, null, setBy);
    }

    /** The same score resting on {@code quotes}, the verified words of the candidate. */
    public CriterionScore withEvidence(List<String> quotes) {
        return new CriterionScore(criterion, rating, weight, rationale, modelRating, cappedBy, setBy, quotes);
    }

    /** True when a rule lowered the model's rating. */
    public boolean capped() {
        return cappedBy != null;
    }

    /** True when a measurement, not the model, decided the rating. */
    public boolean measured() {
        return setBy != null;
    }
}
