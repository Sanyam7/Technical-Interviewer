package com.techinterviewer.evaluation.service;

import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.Misconception;
import com.techinterviewer.interview.port.EvaluationRequest;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** The compact text form of the interview memory that an evaluation request carries (claims and earlier answers). */
final class MemorySummary {

    private static final String ELLIPSIS = "...";

    private MemorySummary() {
    }

    /** At most {@link EvaluationRequest#MAX_MEMORY_SUMMARY_LENGTH} characters; empty for an empty memory. */
    static String of(InterviewMemory memory) {
        StringBuilder text = new StringBuilder();
        if (!memory.candidateClaims().isEmpty()) {
            text.append("Candidate claims: ")
                    .append(memory.candidateClaims().stream().map(Claim::text).collect(Collectors.joining("; ")))
                    .append('\n');
        }
        if (!memory.answerSummaries().isEmpty()) {
            text.append("Earlier answers:\n");
            for (AnswerSummary summary : memory.answerSummaries()) {
                text.append("- ")
                        .append(summary.topic() == null ? "introduction" : summary.topic())
                        .append(String.format(Locale.ROOT, " (score %.0f): ", summary.score()))
                        .append(summary.summary())
                        .append('\n');
            }
        }
        String result = text.toString().strip();
        int max = EvaluationRequest.MAX_MEMORY_SUMMARY_LENGTH;
        return result.length() <= max ? result : result.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }

    /**
     * The claims of the misconceptions the interview recorded, oldest first, at most
     * {@link EvaluationRequest#MAX_EARLIER_MISTAKES}: the earlier mistakes an evaluator may say a new one repeats.
     */
    static List<String> mistakes(InterviewMemory memory) {
        List<String> claims = memory.misconceptions().stream().map(Misconception::claim).distinct().toList();
        int max = EvaluationRequest.MAX_EARLIER_MISTAKES;
        return claims.size() <= max ? claims : claims.subList(claims.size() - max, claims.size());
    }
}
