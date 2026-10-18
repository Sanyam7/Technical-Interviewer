package com.techinterviewer.evaluation;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.CriterionRating;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a system design assessment gives the rating rules besides its ratings (AI_ARCHITECTURE.md section 2.12): how many
 * factual errors concern each dimension, and how many verified quotes back each rating. Used by the application and by the
 * evaluation harness, so a measured design score is the score a candidate gets.
 */
public final class AssessmentEvidence {

    private AssessmentEvidence() {
    }

    /** The evidence of a design answer whose question expects {@code expectedKeyPoints} key points. */
    public static RatingRules.Evidence design(int expectedKeyPoints, AnswerAssessment assessment) {
        Map<Criterion, Integer> errors = new EnumMap<>(Criterion.class);
        assessment.factualErrors().stream().map(FactualError::criterion).filter(Objects::nonNull)
                .forEach(criterion -> errors.merge(criterion, 1, Integer::sum));
        Map<Criterion, Integer> quoted = new EnumMap<>(Criterion.class);
        quotes(assessment).forEach((criterion, found) -> quoted.put(criterion, found.size()));
        return RatingRules.Evidence.design(expectedKeyPoints, assessment.keyPoints(), assessment.factualErrors().size(), assessment.flags(),
                errors, quoted);
    }

    /** The verified quotes of each rated criterion; empty lists for evaluators that quote none. */
    public static Map<Criterion, List<String>> quotes(AnswerAssessment assessment) {
        Map<Criterion, List<String>> quotes = new EnumMap<>(Criterion.class);
        for (Map.Entry<Criterion, CriterionRating> entry : assessment.ratings().entrySet()) {
            quotes.put(entry.getKey(), entry.getValue().evidence());
        }
        return quotes;
    }
}
