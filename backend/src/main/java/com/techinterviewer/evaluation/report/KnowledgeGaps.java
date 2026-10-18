package com.techinterviewer.evaluation.report;

import com.techinterviewer.evaluation.KnowledgeGapAnalyzer;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.port.EvaluationDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Knowledge-gap detection, deterministic: what an evaluated answer showed the candidate does not know. A gap is an
 * expected key point judged MISSING (first) or PARTIAL, or the correction of a factual error, in that order, at most
 * {@value EvaluationDigest#MAX_GAPS} per answer. No model decides what a gap is: the evaluator's verified verdicts do.
 */
final class KnowledgeGaps {

    private KnowledgeGaps() {
    }

    /** The key points of the evaluation judged MISSING or PARTIAL, with the knowledge chunks they cite. */
    static List<KnowledgeGapAnalyzer.ConceptGap> concepts(InterviewQuestion question, Evaluation evaluation) {
        Map<String, ExpectedKeyPoint> expected = question.getExpectedKeyPoints().stream()
                .collect(Collectors.toMap(ExpectedKeyPoint::id, Function.identity(), (a, b) -> a));
        List<KnowledgeGapAnalyzer.ConceptGap> gaps = new ArrayList<>();
        for (KeyPointAssessment assessment : evaluation.getKeyPoints()) {
            ExpectedKeyPoint point = expected.get(assessment.keyPointId());
            if (assessment.status() != KeyPointStatus.COVERED && point != null) {
                gaps.add(new KnowledgeGapAnalyzer.ConceptGap(point.text(), assessment.status(), point.sourceChunkIds()));
            }
        }
        return gaps;
    }

    static List<String> of(InterviewQuestion question, Evaluation evaluation) {
        Map<String, ExpectedKeyPoint> expected = question.getExpectedKeyPoints().stream()
                .collect(Collectors.toMap(ExpectedKeyPoint::id, Function.identity(), (a, b) -> a));
        List<String> gaps = new ArrayList<>();
        for (KeyPointStatus wanted : List.of(KeyPointStatus.MISSING, KeyPointStatus.PARTIAL)) {
            for (KeyPointAssessment assessment : evaluation.getKeyPoints()) {
                ExpectedKeyPoint point = expected.get(assessment.keyPointId());
                if (assessment.status() == wanted && point != null) {
                    gaps.add((wanted == KeyPointStatus.MISSING ? "Missed: " : "Partly covered: ") + point.text());
                }
            }
        }
        for (FactualError error : evaluation.getFactualErrors()) {
            gaps.add("Wrong statement corrected: " + error.correction());
        }
        return gaps.stream()
                .map(gap -> gap.length() <= EvaluationDigest.MAX_GAP_LENGTH ? gap : gap.substring(0, EvaluationDigest.MAX_GAP_LENGTH - 3) + "...")
                .distinct()
                .limit(EvaluationDigest.MAX_GAPS)
                .toList();
    }
}
