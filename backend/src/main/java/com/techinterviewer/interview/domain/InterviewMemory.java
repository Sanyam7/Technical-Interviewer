package com.techinterviewer.interview.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Structured episodic memory of an interview, stored in {@code interviews.memory} and rewritten after every
 * evaluation by the memory updater (AI_ARCHITECTURE.md section 3, DATABASE_SCHEMA.md section 5). Prompts are
 * assembled from it instead of from a transcript. Immutable: updating means building a new instance.
 *
 * @param topicStats            per {@code skill/topic} key: asked count, average, level, last score
 * @param demonstratedKeyPoints {@code skill/topic:keyPointId} entries the candidate has already shown
 * @param candidateClaims       claims made by the candidate, with whether they were probed
 * @param answerSummaries       one line per answered question, oldest first
 * @param flags                 count per {@link com.techinterviewer.evaluation.domain.EvaluationFlag} name
 * @param sectionProgress       slots used and start time per started section
 * @param pendingStep           the next question that is decided but not generated yet, if any
 * @param overall               what the interview has learned about the candidate as a whole, from every scored
 *                              technical answer (the introduction excluded): {@code asked} counts those answers; null
 *                              before the first one. Its level is the candidate's current level.
 * @param misconceptions        factual errors the candidate made, oldest first, at most {@value #MAX_MISCONCEPTIONS}
 * @param resumeItemsAsked      résumé experiences (ids such as x3) the engine has anchored a question on, in order; each is
 *                              anchored at most once (AI_ARCHITECTURE.md section 2.13)
 * @param stageQuestions        main questions of the questioning section the engine has decided per stage (AI_ARCHITECTURE.md
 *                              section 2.15); empty without stages
 */
public record InterviewMemory(
        Map<String, TopicStat> topicStats,
        List<String> demonstratedKeyPoints,
        List<Claim> candidateClaims,
        List<AnswerSummary> answerSummaries,
        Map<String, Integer> flags,
        Map<Section, SectionProgress> sectionProgress,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable PendingStep pendingStep,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable TopicStat overall,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Misconception> misconceptions,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> resumeItemsAsked,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<InterviewStage, Integer> stageQuestions) {

    public static final int MAX_MISCONCEPTIONS = 10;
    public static final int MAX_RESUME_ITEMS = 50;

    public InterviewMemory {
        topicStats = Immutables.map(topicStats);
        demonstratedKeyPoints = Immutables.list(demonstratedKeyPoints);
        candidateClaims = Immutables.list(candidateClaims);
        answerSummaries = Immutables.list(answerSummaries);
        flags = Immutables.map(flags);
        sectionProgress = Immutables.enumMap(sectionProgress, Section.class);
        misconceptions = Immutables.list(misconceptions);
        Require.between(misconceptions.size(), 0, MAX_MISCONCEPTIONS, "misconceptions size");
        resumeItemsAsked = Immutables.list(resumeItemsAsked);
        Require.between(resumeItemsAsked.size(), 0, MAX_RESUME_ITEMS, "resumeItemsAsked size");
        resumeItemsAsked.forEach(id -> Require.notBlank(id, "resumeItemsAsked entry", 10));
        stageQuestions = Immutables.enumMap(stageQuestions, InterviewStage.class);
        stageQuestions.forEach((stage, count) -> Require.between(count, 0, SectionPlan.MAX_SLOTS, "questions of stage " + stage));
        topicStats.keySet().forEach(topic -> Require.notBlank(topic, "topicStats key"));
        demonstratedKeyPoints.forEach(keyPoint -> Require.notBlank(keyPoint, "demonstratedKeyPoints entry", 200));
        flags.forEach((flag, count) -> {
            Require.notBlank(flag, "flags key");
            Require.atLeast(count, 0, "count of flag " + flag);
        });
    }

    /** A memory without stages (as stored before they existed). */
    public InterviewMemory(
            Map<String, TopicStat> topicStats,
            List<String> demonstratedKeyPoints,
            List<Claim> candidateClaims,
            List<AnswerSummary> answerSummaries,
            Map<String, Integer> flags,
            Map<Section, SectionProgress> sectionProgress,
            @Nullable PendingStep pendingStep,
            @Nullable TopicStat overall,
            List<Misconception> misconceptions,
            List<String> resumeItemsAsked) {
        this(topicStats, demonstratedKeyPoints, candidateClaims, answerSummaries, flags, sectionProgress, pendingStep, overall, misconceptions,
                resumeItemsAsked, Map.of());
    }

    /** A memory without résumé anchors (as stored before they existed). */
    public InterviewMemory(
            Map<String, TopicStat> topicStats,
            List<String> demonstratedKeyPoints,
            List<Claim> candidateClaims,
            List<AnswerSummary> answerSummaries,
            Map<String, Integer> flags,
            Map<Section, SectionProgress> sectionProgress,
            @Nullable PendingStep pendingStep,
            @Nullable TopicStat overall,
            List<Misconception> misconceptions) {
        this(topicStats, demonstratedKeyPoints, candidateClaims, answerSummaries, flags, sectionProgress, pendingStep, overall, misconceptions,
                List.of());
    }

    /** A memory without the candidate-wide fields (as stored before they existed). */
    public InterviewMemory(
            Map<String, TopicStat> topicStats,
            List<String> demonstratedKeyPoints,
            List<Claim> candidateClaims,
            List<AnswerSummary> answerSummaries,
            Map<String, Integer> flags,
            Map<Section, SectionProgress> sectionProgress,
            @Nullable PendingStep pendingStep) {
        this(topicStats, demonstratedKeyPoints, candidateClaims, answerSummaries, flags, sectionProgress, pendingStep, null, List.of(),
                List.of());
    }

    /** The memory of an interview that has not started. */
    public static InterviewMemory empty() {
        return new InterviewMemory(Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), null, null, List.of(), List.of(), Map.of());
    }

    /** True when the next question has been decided but not generated yet. */
    public boolean awaitingNextQuestion() {
        return pendingStep != null;
    }

    public InterviewMemory withPendingStep(@Nullable PendingStep step) {
        return new InterviewMemory(topicStats, demonstratedKeyPoints, candidateClaims, answerSummaries, flags, sectionProgress, step,
                overall, misconceptions, resumeItemsAsked, stageQuestions);
    }

    public InterviewMemory withCandidateClaims(List<Claim> claims) {
        return new InterviewMemory(topicStats, demonstratedKeyPoints, claims, answerSummaries, flags, sectionProgress, pendingStep,
                overall, misconceptions, resumeItemsAsked, stageQuestions);
    }

    public InterviewMemory withSectionProgress(Map<Section, SectionProgress> progress) {
        return new InterviewMemory(topicStats, demonstratedKeyPoints, candidateClaims, answerSummaries, flags, progress, pendingStep,
                overall, misconceptions, resumeItemsAsked, stageQuestions);
    }

    /** The memory after the engine anchored a question on résumé experience {@code itemId}. */
    public InterviewMemory withResumeItemAsked(String itemId) {
        List<String> asked = new java.util.ArrayList<>(resumeItemsAsked);
        asked.add(itemId);
        return new InterviewMemory(topicStats, demonstratedKeyPoints, candidateClaims, answerSummaries, flags, sectionProgress, pendingStep,
                overall, misconceptions, asked, stageQuestions);
    }

    /** The memory after the engine decided a main question of {@code stage}. */
    public InterviewMemory withStageQuestion(InterviewStage stage) {
        Map<InterviewStage, Integer> counts = new java.util.EnumMap<>(InterviewStage.class);
        counts.putAll(stageQuestions);
        counts.merge(stage, 1, Integer::sum);
        return new InterviewMemory(topicStats, demonstratedKeyPoints, candidateClaims, answerSummaries, flags, sectionProgress, pendingStep,
                overall, misconceptions, resumeItemsAsked, counts);
    }

    /** Main questions of {@code stage} the engine has decided so far. */
    public int stageQuestionsOf(InterviewStage stage) {
        return stageQuestions.getOrDefault(stage, 0);
    }

    /** The candidate's current level: the overall level, or {@code start} before any technical answer was scored. */
    public int currentLevel(int start) {
        return overall == null ? start : overall.level();
    }
}
