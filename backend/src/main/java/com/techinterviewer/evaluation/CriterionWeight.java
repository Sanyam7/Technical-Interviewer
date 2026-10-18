package com.techinterviewer.evaluation;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import java.math.BigDecimal;

/** The share of the overall score that one criterion carries, above 0 and at most 1. */
public record CriterionWeight(Criterion criterion, BigDecimal weight) {

    public CriterionWeight {
        Require.nonNull(criterion, "criterion");
        Require.nonNull(weight, "weight");
        if (weight.signum() <= 0 || weight.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("weight must be above 0 and at most 1 but was " + weight);
        }
    }

    public static CriterionWeight of(Criterion criterion, String weight) {
        return new CriterionWeight(criterion, new BigDecimal(weight));
    }
}
