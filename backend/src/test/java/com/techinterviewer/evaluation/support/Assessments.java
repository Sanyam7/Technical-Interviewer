package com.techinterviewer.evaluation.support;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.EvaluatorConfidence;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.CriterionRating;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Builders of assessments as an evaluator would return them. */
public final class Assessments {

    private Assessments() {
    }

    /** Every criterion of the evaluator's rubric rated {@code rating}, so the overall score is {@code rating / 4 * 100}. */
    public static Map<Criterion, CriterionRating> uniformRatings(Evaluator evaluator, int rating) {
        Map<Criterion, CriterionRating> ratings = new EnumMap<>(Criterion.class);
        for (Criterion criterion : RubricRegistry.forEvaluator(evaluator).criteria()) {
            ratings.put(criterion, rated(evaluator, rating, criterion));
        }
        return ratings;
    }

    /** A design dimension rests on a (verified) quote, as a real evaluation's would; the others need none. */
    private static CriterionRating rated(Evaluator evaluator, int rating, Criterion criterion) {
        return new CriterionRating(rating, "because " + criterion,
                evaluator == Evaluator.SYSTEM_DESIGN && rating > 0 ? List.of("the candidate's words") : List.of());
    }

    /** A valid assessment: uniform ratings, every expected key point covered, the given flags. */
    public static AnswerAssessment uniform(
            Evaluator evaluator, int rating, List<ExpectedKeyPoint> expected, EvaluationFlag... flags) {
        return builder(evaluator).rating(rating).keyPointsCovered(expected).flags(flags).build();
    }

    public static Builder builder(Evaluator evaluator) {
        return new Builder(evaluator);
    }

    /** A mutable builder with valid defaults; each method changes one aspect. */
    public static final class Builder {

        private Map<Criterion, CriterionRating> ratings;
        private List<KeyPointAssessment> keyPoints = new ArrayList<>();
        private List<String> claims = new ArrayList<>();
        private String summary = "Summary of the answer.";
        private Set<EvaluationFlag> flags = EnumSet.noneOf(EvaluationFlag.class);
        private String model = "scripted-model";
        private List<FactualError> errors = new ArrayList<>();
        private @Nullable EvaluatorConfidence confidence;
        private final Evaluator evaluator;

        private Builder(Evaluator evaluator) {
            this.evaluator = evaluator;
            this.ratings = new EnumMap<>(uniformRatings(evaluator, 3));
        }

        public Builder rating(int rating) {
            Map<Criterion, CriterionRating> next = new EnumMap<>(Criterion.class);
            ratings.keySet().forEach(criterion -> next.put(criterion, rated(evaluator, rating, criterion)));
            this.ratings = next;
            return this;
        }

        public Builder ratings(Map<Criterion, CriterionRating> ratings) {
            this.ratings = new EnumMap<>(Criterion.class);
            this.ratings.putAll(ratings);
            return this;
        }

        public Builder rate(Criterion criterion, int rating) {
            ratings.put(criterion, rated(evaluator, rating, criterion));
            return this;
        }

        public Builder without(Criterion criterion) {
            ratings.remove(criterion);
            return this;
        }

        public Builder keyPointsCovered(List<ExpectedKeyPoint> expected) {
            keyPoints = new ArrayList<>();
            expected.forEach(keyPoint -> keyPoints.add(new KeyPointAssessment(keyPoint.id(), KeyPointStatus.COVERED, "a quote", true)));
            return this;
        }

        public Builder keyPoint(String id, KeyPointStatus status) {
            keyPoints.add(new KeyPointAssessment(id, status, null, false));
            return this;
        }

        public Builder claims(List<String> claims) {
            this.claims = new ArrayList<>(claims);
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder flags(EvaluationFlag... flags) {
            this.flags = EnumSet.noneOf(EvaluationFlag.class);
            this.flags.addAll(List.of(flags));
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder errors(FactualError... errors) {
            this.errors = new ArrayList<>(List.of(errors));
            return this;
        }

        public Builder confidence(Confidence level, String reason) {
            this.confidence = new EvaluatorConfidence(level, reason);
            return this;
        }

        public AnswerAssessment build() {
            return new AnswerAssessment(ratings, keyPoints, errors, claims, summary, flags, "evaluate-answer", "1.0", model, null, confidence);
        }
    }
}
