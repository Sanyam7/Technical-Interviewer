package com.techinterviewer.evaluation;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.CriterionScore;
import java.util.List;

/**
 * The server-computed result for one answer.
 *
 * @param overall         0 to 100, two decimals
 * @param criterionScores one entry per rubric criterion, in rubric order, carrying the rubric's weight; ready to store in
 *                        {@code evaluations.criterion_scores}
 */
public record AggregatedScore(double overall, List<CriterionScore> criterionScores) {

    public AggregatedScore {
        Require.between(overall, 0, 100, "overall");
        criterionScores = Immutables.list(criterionScores);
    }
}
