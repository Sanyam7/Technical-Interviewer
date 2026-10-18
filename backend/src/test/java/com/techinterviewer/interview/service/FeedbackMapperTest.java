package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.EvaluationFindings;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.domain.ModelProvenance;
import com.techinterviewer.interview.api.dto.CriterionFeedback;
import com.techinterviewer.interview.api.dto.FeedbackResponse;
import com.techinterviewer.interview.api.dto.KeyPointFeedback;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeedbackMapperTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");

    private static InterviewQuestion question() {
        return InterviewQuestion.root(UUID.randomUUID(), 2, QuestionKind.TECHNICAL, null, 3, new QuestionContent(
                "Explain volatile.", null,
                List.of(new ExpectedKeyPoint("kp1", "visibility", List.of()), new ExpectedKeyPoint("kp2", "ordering", List.of()),
                        new ExpectedKeyPoint("kp3", "atomicity", List.of())),
                "technical.v1", QuestionOrigin.GENERATED, null), T0);
    }

    private static Evaluation evaluation(List<KeyPointAssessment> keyPoints) {
        EvaluationFindings findings = new EvaluationFindings(
                List.of(new CriterionScore(Criterion.CORRECTNESS, 3, 0.4, "right"), new CriterionScore(Criterion.DEPTH, 0, 0.6, "")),
                keyPoints, List.of(), List.of(), "A summary.", Set.of());
        return Evaluation.create(UUID.randomUUID(), Evaluator.TECHNICAL, new BigDecimal("57.5"), findings,
                new ModelProvenance("p", "1", "m", null), T0);
    }

    @Test
    void scoreCriteriaKeyPointsAndSummaryAreMapped() {
        FeedbackResponse feedback = FeedbackMapper.toFeedback(evaluation(List.of(
                new KeyPointAssessment("kp2", KeyPointStatus.PARTIAL, "something", false),
                new KeyPointAssessment("kp1", KeyPointStatus.COVERED, "visible", true),
                new KeyPointAssessment("kp3", KeyPointStatus.MISSING, null, false))), question());

        assertThat(feedback.overallScore()).isEqualByComparingTo("57.50");
        assertThat(feedback.criteria()).containsExactly(
                new CriterionFeedback(Criterion.CORRECTNESS, 3, "right"), new CriterionFeedback(Criterion.DEPTH, 0, ""));
        assertThat(feedback.keyPoints()).containsExactly(
                new KeyPointFeedback("ordering", KeyPointStatus.PARTIAL),
                new KeyPointFeedback("visibility", KeyPointStatus.COVERED),
                new KeyPointFeedback("atomicity", KeyPointStatus.MISSING));
        assertThat(feedback.summary()).isEqualTo("A summary.");
    }

    @Test
    void anAssessmentOfAKeyPointTheQuestionDoesNotHaveIsLeftOut() {
        FeedbackResponse feedback = FeedbackMapper.toFeedback(evaluation(List.of(
                new KeyPointAssessment("kp1", KeyPointStatus.COVERED, "visible", true),
                new KeyPointAssessment("ghost", KeyPointStatus.COVERED, "x", false))), question());

        assertThat(feedback.keyPoints()).containsExactly(new KeyPointFeedback("visibility", KeyPointStatus.COVERED));
    }

    @Test
    void noKeyPointsMeansNoKeyPointFeedback() {
        assertThat(FeedbackMapper.toFeedback(evaluation(List.of()), question()).keyPoints()).isEmpty();
    }
}
