package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the interview showed the candidate does not know, computed without a model from the evaluations
 * ({@code KnowledgeGapAnalyzer}); stored in {@code reports.knowledge_gaps}.
 *
 * @param missedConcepts   expected key points judged MISSING or PARTIAL, in the order they were asked
 * @param misconceptions   wrong statements, with their corrections, in the order they were made
 * @param weakTopics       topics whose score is below the weak threshold, weakest first
 * @param repeatedMistakes the same gap shown in more than one question
 */
public record KnowledgeGapReport(
        List<MissedConcept> missedConcepts,
        List<MisconceptionFinding> misconceptions,
        List<WeakTopic> weakTopics,
        List<RepeatedMistake> repeatedMistakes) {

    public static final KnowledgeGapReport EMPTY = new KnowledgeGapReport(List.of(), List.of(), List.of(), List.of());

    public KnowledgeGapReport {
        missedConcepts = Immutables.list(missedConcepts);
        misconceptions = Immutables.list(misconceptions);
        weakTopics = Immutables.list(weakTopics);
        repeatedMistakes = Immutables.list(repeatedMistakes);
    }

    /** A catalog topic: its key ({@code skill/topic}) and its display name. */
    public record GapTopic(String key, String name) {

        public GapTopic {
            Require.notBlank(key, "key", 160);
            Require.notBlank(name, "name", 300);
        }
    }

    /** An expected key point the answer missed or covered only partly. */
    public record MissedConcept(String concept, KeyPointStatus status, @Nullable GapTopic topic, UUID questionId) {

        public MissedConcept {
            Require.notBlank(concept, "concept", 500);
            if (status == KeyPointStatus.COVERED) {
                throw new IllegalArgumentException("a covered key point is not a missed concept");
            }
            Require.nonNull(questionId, "questionId");
        }
    }

    /** A wrong statement and what is true. */
    public record MisconceptionFinding(String claim, String correction, @Nullable GapTopic topic, UUID questionId) {

        public MisconceptionFinding {
            Require.notBlank(claim, "claim", FactualError.MAX_LENGTH);
            Require.notBlank(correction, "correction", FactualError.MAX_LENGTH);
            Require.nonNull(questionId, "questionId");
        }
    }

    /** A topic the candidate answered weakly: its difficulty-weighted score and how many answers it rests on. */
    public record WeakTopic(GapTopic topic, double score, int evidenceCount) {

        public WeakTopic {
            Require.nonNull(topic, "topic");
            Require.between(score, 0, 100, "score");
            Require.atLeast(evidenceCount, 1, "evidenceCount");
        }
    }

    /**
     * The same gap in more than one question.
     *
     * @param description what was repeated, in the words of its first occurrence
     * @param kind        a wrong belief (at least one occurrence is a factual error), or a concept missed each time
     * @param basis       how the occurrences were linked
     * @param questionIds the questions it occurred in, in the order they were asked; at least two
     * @param topics      the distinct topics of those questions
     */
    public record RepeatedMistake(String description, Kind kind, Set<Basis> basis, List<UUID> questionIds, List<GapTopic> topics) {

        public enum Kind {
            MISCONCEPTION,
            MISSED_CONCEPT
        }

        public enum Basis {
            /** The evaluator said a new mistake repeats an earlier one, by a token the server validated. */
            EVALUATOR_LINK,
            /** The gaps of two questions cite the same knowledge-base chunk: the same piece of knowledge was missing twice. */
            SHARED_SOURCE,
            /** Two questions show the same gap in the same words (ignoring case and spacing). */
            SAME_WORDING
        }

        public RepeatedMistake {
            Require.notBlank(description, "description", 1200);
            Require.nonNull(kind, "kind");
            basis = Immutables.enumSet(basis, Basis.class);
            if (basis.isEmpty()) {
                throw new IllegalArgumentException("a repeated mistake has a basis");
            }
            questionIds = Immutables.list(questionIds);
            if (questionIds.size() < 2 || Set.copyOf(questionIds).size() != questionIds.size()) {
                throw new IllegalArgumentException("a repeated mistake occurs in at least two distinct questions");
            }
            topics = Immutables.list(topics);
        }
    }
}
