package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionOrigin;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A question ready to be asked: already validated by the AI layer (topic, difficulty, grounding, no answer
 * leakage, no duplicate). The interview service stores it as is.
 *
 * @param prompt             the question text shown to the candidate
 * @param expectedKeyPoints  what a good answer contains; server-only
 * @param details            coding material (constraints, examples, signature), shown to the candidate
 * @param origin             GENERATED or EXEMPLAR_FALLBACK (never STATIC: the server writes the introduction itself)
 * @param difficulty         level actually delivered, 1 to 5
 * @param topicSlug          topic of the question as a {@code skill/topic} key, absent without a catalog topic
 * @param estimatedMinutes   suggested time, 1 to 60
 * @param groundingChunkIds  knowledge chunks the question was grounded in
 * @param promptId           prompt template that produced it (required when GENERATED)
 * @param promptVersion      its version (required when GENERATED)
 * @param model              model that answered (required when GENERATED)
 * @param llmCallId          id of the recorded model call, stored as {@code questions.generation_llm_call_id}
 * @param embedding          the question's vector for de-duplication; null when no embedding model is configured or it failed
 */
public record GeneratedQuestion(
        String prompt,
        List<ExpectedKeyPoint> expectedKeyPoints,
        @Nullable QuestionDetails details,
        QuestionOrigin origin,
        int difficulty,
        @Nullable String topicSlug,
        int estimatedMinutes,
        List<UUID> groundingChunkIds,
        @Nullable String promptId,
        @Nullable String promptVersion,
        @Nullable String model,
        @Nullable UUID llmCallId,
        @Nullable QuestionEmbedding embedding) {

    public static final int MAX_PROMPT_LENGTH = 4000;
    public static final int MAX_KEY_POINTS = 10;

    public GeneratedQuestion {
        Require.notBlank(prompt, "prompt", MAX_PROMPT_LENGTH);
        expectedKeyPoints = Immutables.list(expectedKeyPoints);
        Require.between(expectedKeyPoints.size(), 0, MAX_KEY_POINTS, "expectedKeyPoints size");
        Set<String> ids = new HashSet<>();
        for (ExpectedKeyPoint keyPoint : expectedKeyPoints) {
            if (!ids.add(keyPoint.id())) {
                throw new IllegalArgumentException("duplicate key point id " + keyPoint.id());
            }
        }
        Require.nonNull(origin, "origin");
        if (origin == QuestionOrigin.STATIC) {
            throw new IllegalArgumentException("STATIC questions are written by the server, not by a generator");
        }
        Require.between(difficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "difficulty");
        topicSlug = Require.nullOrNotBlank(topicSlug, "topicSlug", 160);
        Require.between(estimatedMinutes, 1, 60, "estimatedMinutes");
        groundingChunkIds = Immutables.list(groundingChunkIds);
        promptId = Require.nullOrNotBlank(promptId, "promptId", 60);
        promptVersion = Require.nullOrNotBlank(promptVersion, "promptVersion", 20);
        model = Require.nullOrNotBlank(model, "model", 80);
        if (origin == QuestionOrigin.GENERATED && (promptId == null || promptVersion == null || model == null)) {
            throw new IllegalArgumentException("a GENERATED question needs promptId, promptVersion and model");
        }
    }

    /** A question without an embedding. */
    public GeneratedQuestion(String prompt, List<ExpectedKeyPoint> expectedKeyPoints, @Nullable QuestionDetails details,
            QuestionOrigin origin, int difficulty, @Nullable String topicSlug, int estimatedMinutes, List<UUID> groundingChunkIds,
            @Nullable String promptId, @Nullable String promptVersion, @Nullable String model, @Nullable UUID llmCallId) {
        this(prompt, expectedKeyPoints, details, origin, difficulty, topicSlug, estimatedMinutes, groundingChunkIds, promptId,
                promptVersion, model, llmCallId, null);
    }

    public GeneratedQuestion withEmbedding(@Nullable QuestionEmbedding vector) {
        return new GeneratedQuestion(prompt, expectedKeyPoints, details, origin, difficulty, topicSlug, estimatedMinutes,
                groundingChunkIds, promptId, promptVersion, model, llmCallId, vector);
    }
}
