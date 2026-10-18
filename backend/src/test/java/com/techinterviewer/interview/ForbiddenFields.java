package com.techinterviewer.interview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Finds what a candidate-facing JSON document must never contain, anywhere in its tree: what the interview knows and the
 * candidate must not (expected key points, rubrics, difficulty of a question, where a question came from, the memory and
 * the quotas of the plan, links to model calls) and every score before the interview is COMPLETED. Property names are
 * compared case-insensitively and exactly, at any depth, inside arrays too.
 */
public final class ForbiddenFields {

    /** Never in any response, wherever it appears. */
    public static final Set<String> SERVER_SIDE = lower(
            "expectedKeyPoints", "expectedKeyPointIds", "keyPointIds", "rubric", "rubricId", "origin", "provenance", "promptId",
            "promptVersion", "model", "llmCallId", "generationLlmCallId", "evaluationLlmCallId", "sourceChunkIds",
            "groundingChunkIds", "evidenceQuote", "verified", "memory", "topicStats", "demonstratedKeyPoints", "candidateClaims",
            "answerSummaries", "flags", "sectionProgress", "pendingStep", "resumeState", "currentQuestionId", "topicQuotas",
            "quotas", "slots", "band", "maxFollowUpDepth", "evaluationStatus", "difficultyLevel", "level", "targetDifficulty",
            "difficultyBand", "startLevel", "weight", "weights", "factualErrors", "candidateId", "ownerUserId", "userId",
            // introduced by the turn: how an answer was judged and why the next question is what it is
            "assessment", "evaluation", "evaluations", "evaluationId", "followUpMode", "targetKeyPointId", "targetKeyPoint",
            "targetClaim", "parentQuestionId", "followUpDepth", "timeTakenSeconds", "codeLanguage");

    /** Scores and ratings: only the feedback of a transcript entry of a COMPLETED interview may carry them. */
    public static final Set<String> SCORES = lower(
            "score", "scores", "overallScore", "overall", "rating", "ratings", "criteria", "criterion", "criterionScores",
            "keyPoints", "keyPointAssessments", "recommendation", "confidence", "skillScores", "sectionScores", "percentile");

    /** The feedback of one answer: the property that holds scores. */
    public static final String FEEDBACK = "feedback";

    /**
     * The only place a difficulty may appear: the difficulty the candidate asked for, in a configuration. A configuration
     * is recognised by its shape (it states the experience level of the candidate), wherever it is embedded.
     */
    private static final String CONFIGURATION_MARKER = "experienceLevel";

    private ForbiddenFields() {
    }

    /**
     * @param feedbackAllowed whether the document is the transcript of a COMPLETED interview, whose entries may carry feedback
     * @return the path of every offending property, empty when the document is clean
     */
    public static List<String> find(JsonNode document, boolean feedbackAllowed) {
        List<String> found = new ArrayList<>();
        walk(document, "$", false, feedbackAllowed, found);
        return found;
    }

    private static void walk(JsonNode node, String path, boolean insideFeedback, boolean feedbackAllowed, List<String> found) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", insideFeedback, feedbackAllowed, found);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        boolean configuration = node.has(CONFIGURATION_MARKER);
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            String name = property.getKey();
            String key = name.toLowerCase(Locale.ROOT);
            String childPath = path + "." + name;
            JsonNode value = property.getValue();
            if (SERVER_SIDE.contains(key)) {
                found.add(childPath);
            } else if (key.equals("difficulty") && !configuration) {
                found.add(childPath);
            } else if (key.equals(FEEDBACK)) {
                if (!value.isNull() && !feedbackAllowed) {
                    found.add(childPath);
                }
            } else if (SCORES.contains(key) && !insideFeedback) {
                found.add(childPath);
            }
            walk(value, childPath, insideFeedback || key.equals(FEEDBACK), feedbackAllowed, found);
        }
    }

    private static Set<String> lower(String... names) {
        return Stream.of(names).map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
