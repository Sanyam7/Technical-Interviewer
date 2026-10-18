package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.CriterionScore;
import java.util.List;

/**
 * The evaluator's judgement of one criterion: a rating from 0 to 4, a short rationale and, for a system design dimension, the
 * candidate's words it rests on.
 *
 * @param evidence quotes of the candidate that the server found in the candidate's text (unverified ones are already dropped);
 *                 empty for evaluators that quote no evidence per criterion
 */
public record CriterionRating(int rating, String rationale, List<String> evidence) {

    public CriterionRating {
        Require.between(rating, CriterionScore.MIN_RATING, CriterionScore.MAX_RATING, "rating");
        Require.text(rationale, "rationale", CriterionScore.MAX_RATIONALE_LENGTH);
        evidence = Immutables.list(evidence);
        if (evidence.size() > CriterionScore.MAX_EVIDENCE) {
            throw new IllegalArgumentException("a criterion keeps at most " + CriterionScore.MAX_EVIDENCE + " quotes");
        }
    }

    public CriterionRating(int rating, String rationale) {
        this(rating, rationale, List.of());
    }
}
