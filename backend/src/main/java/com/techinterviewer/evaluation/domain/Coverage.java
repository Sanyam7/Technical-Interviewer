package com.techinterviewer.evaluation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How much of an interview a report is based on ({@code reports.coverage}).
 *
 * <p>The assessment weight of a question is its difficulty level (the weight it has in the scores): the weight of a report
 * is the sum over the scored turns of the technical sections, an unanswered-by-skip root question included, the introduction
 * excluded. {@code intendedWeight} is that sum over every turn the candidate was given, {@code assessedWeight} the part of it
 * that could be assessed. Without unassessable answers the two are equal.
 *
 * @param questionsAnswered questions the candidate answered
 * @param skipped           questions the candidate skipped
 * @param followUps         follow-up questions that were asked
 * @param minutesUsed       minutes between start and end
 * @param assessedWeight    assessment weight that could be assessed, at most {@code intendedWeight}
 * @param intendedWeight    assessment weight of the technical turns of the interview
 * @param unassessable      the answers that could not be assessed, in the order they were asked
 */
public record Coverage(
        int questionsAnswered,
        int skipped,
        int followUps,
        int minutesUsed,
        int assessedWeight,
        int intendedWeight,
        List<UnassessableAnswer> unassessable) {

    public Coverage {
        Require.atLeast(questionsAnswered, 0, "questionsAnswered");
        Require.atLeast(skipped, 0, "skipped");
        Require.atLeast(followUps, 0, "followUps");
        Require.atLeast(minutesUsed, 0, "minutesUsed");
        Require.atLeast(assessedWeight, 0, "assessedWeight");
        Require.atLeast(intendedWeight, assessedWeight, "intendedWeight");
        unassessable = Immutables.list(unassessable);
    }

    /** Coverage of an interview in which every answer could be assessed. */
    public Coverage(int questionsAnswered, int skipped, int followUps, int minutesUsed) {
        this(questionsAnswered, skipped, followUps, minutesUsed, 0, 0, List.of());
    }

    /**
     * Reads a stored document. The assessment fields did not exist when the first reports were stored: when they are absent
     * (and a primitive may not be read from a missing value), the report counts as fully assessed, which is what it was.
     */
    @JsonCreator
    static Coverage fromJson(
            @JsonProperty("questionsAnswered") int questionsAnswered,
            @JsonProperty("skipped") int skipped,
            @JsonProperty("followUps") int followUps,
            @JsonProperty("minutesUsed") int minutesUsed,
            @JsonProperty("assessedWeight") @Nullable Integer assessedWeight,
            @JsonProperty("intendedWeight") @Nullable Integer intendedWeight,
            @JsonProperty("unassessable") @Nullable List<UnassessableAnswer> unassessable) {
        return new Coverage(questionsAnswered, skipped, followUps, minutesUsed,
                assessedWeight == null ? 0 : assessedWeight, intendedWeight == null ? 0 : intendedWeight, unassessable);
    }

    /**
     * The share of the intended assessment weight that could be assessed, from 0 to 1 with two decimals, rounded down so that
     * a share below a threshold never displays as reaching it. Nothing to assess is full coverage, unless an answer was
     * unassessable (then it is 0: there is nothing to base a verdict on).
     */
    public BigDecimal assessmentCoverage() {
        if (intendedWeight == 0) {
            return unassessable.isEmpty() ? BigDecimal.ONE.setScale(2) : BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(assessedWeight).divide(BigDecimal.valueOf(intendedWeight), 2, RoundingMode.DOWN);
    }
}
