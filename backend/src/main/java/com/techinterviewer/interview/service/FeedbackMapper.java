package com.techinterviewer.interview.service;

import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.service.CodeEvidence;
import com.techinterviewer.evaluation.ScoreNotes;
import com.techinterviewer.evaluation.TestEvidence;
import com.techinterviewer.evaluation.domain.CodeAnalysis;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.interview.api.dto.CodeAnalysisFeedback;
import com.techinterviewer.interview.api.dto.CodeTestsFeedback;
import com.techinterviewer.interview.api.dto.CriterionFeedback;
import com.techinterviewer.interview.api.dto.FeedbackResponse;
import com.techinterviewer.interview.api.dto.KeyPointFeedback;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.InterviewQuestion;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Maps the evaluation of an answer to the feedback of the transcript. Only the use case that knows the interview is
 * COMPLETED may call it: the feedback carries scores, which are never shown before.
 */
public final class FeedbackMapper {

    private FeedbackMapper() {
    }

    /**
     * Key points are shown with their text, in the order the evaluator assessed them. An assessment of a key point the
     * question does not have is left out.
     */
    public static FeedbackResponse toFeedback(Evaluation evaluation, InterviewQuestion question) {
        return toFeedback(evaluation, question, null, null);
    }

    /**
     * The feedback of an answer, with the test results and the complexity analysis of a coding solution.
     *
     * @param tests   the stored test run of the answer, if it was a tested solution
     * @param problem the curated problem of the question, if it has one
     */
    public static FeedbackResponse toFeedback(Evaluation evaluation, InterviewQuestion question, @Nullable TestRunReport tests,
            @Nullable CodingProblem problem) {
        Map<String, String> texts = question.getExpectedKeyPoints().stream()
                .collect(Collectors.toMap(ExpectedKeyPoint::id, ExpectedKeyPoint::text, (first, second) -> first));
        List<KeyPointFeedback> keyPoints = evaluation.getKeyPoints().stream()
                .filter(assessment -> texts.containsKey(assessment.keyPointId()))
                .map(assessment -> new KeyPointFeedback(texts.get(assessment.keyPointId()), assessment.status()))
                .toList();
        TestEvidence evidence = tests == null ? null : CodeEvidence.forScoring(tests);
        List<CriterionFeedback> criteria = evaluation.getCriterionScores().stream()
                .map(score -> new CriterionFeedback(score.criterion(), score.rating(), score.rationale(), basis(score),
                        score.modelRating(), ScoreNotes.note(score, evidence), score.evidence()))
                .toList();
        CodeTestsFeedback codeTests = tests == null ? null : new CodeTestsFeedback(tests.status().name(), tests.language(), tests.passed(),
                tests.total(), CodeResultViews.of(tests, problem), tests.compileOutput(), tests.durationMs());
        CodeAnalysis analysis = evaluation.getCodeAnalysis();
        CodeAnalysisFeedback codeAnalysis = analysis == null ? null : new CodeAnalysisFeedback(analysis.approach(), analysis.timeComplexity(),
                analysis.spaceComplexity(), problem == null ? null : problem.complexity().time(),
                problem == null ? null : problem.complexity().space());
        return new FeedbackResponse(evaluation.getOverallScore(), criteria, keyPoints, evaluation.getSummary(), codeTests, codeAnalysis);
    }

    private static String basis(CriterionScore score) {
        if (score.measured()) {
            return "TESTS";
        }
        return score.capped() ? "CAPPED" : "MODEL";
    }
}
