package com.techinterviewer.evaluation;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The criteria an evaluator rates and how much each counts. The weights are decimal numbers that add up to exactly 1,
 * so the score arithmetic has no floating-point error.
 *
 * @param id        stable identifier stored with each question ({@code questions.rubric_id}), for example {@code technical.v1}
 * @param evaluator the evaluator that uses the rubric
 * @param weights   the criteria in display order; at least one, each criterion once
 */
public record Rubric(String id, Evaluator evaluator, List<CriterionWeight> weights) {

    public Rubric {
        Require.notBlank(id, "id", 40);
        Require.nonNull(evaluator, "evaluator");
        weights = Immutables.list(weights);
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("a rubric has at least one criterion");
        }
        Set<Criterion> seen = EnumSet.noneOf(Criterion.class);
        BigDecimal total = BigDecimal.ZERO;
        for (CriterionWeight weight : weights) {
            if (!seen.add(weight.criterion())) {
                throw new IllegalArgumentException("criterion " + weight.criterion() + " appears twice");
            }
            total = total.add(weight.weight());
        }
        if (total.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("the weights must add up to 1 but add up to " + total);
        }
    }

    /** The criteria this rubric rates. */
    public Set<Criterion> criteria() {
        Set<Criterion> criteria = EnumSet.noneOf(Criterion.class);
        weights.forEach(weight -> criteria.add(weight.criterion()));
        return criteria;
    }
}
