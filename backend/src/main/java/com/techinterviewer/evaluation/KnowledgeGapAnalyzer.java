package com.techinterviewer.evaluation;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport.GapTopic;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport.MisconceptionFinding;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport.MissedConcept;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport.RepeatedMistake;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport.WeakTopic;
import com.techinterviewer.interview.domain.Section;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Knowledge-gap detection over a whole interview (AI_ARCHITECTURE.md section 2.10). Deterministic: the model's verified
 * verdicts decide what a gap is, this class only collects and connects them.
 *
 * <ul>
 *   <li><b>Missed concepts:</b> expected key points judged MISSING or PARTIAL (after the server's evidence check).
 *   <li><b>Misconceptions:</b> the factual errors, with their corrections.
 *   <li><b>Weak topics:</b> topics whose difficulty-weighted score is below {@value #WEAK_TOPIC_SCORE}, the threshold
 *       below which the adaptive engine also calls a topic WEAK.
 *   <li><b>Repeated mistakes:</b> one gap shown in two or more questions. Two gaps are the same when the evaluator
 *       linked a new factual error to an earlier one (a token the server validated, mapped back to the earlier claim),
 *       when gaps of different question threads cite the same knowledge-base chunk (the same piece of knowledge was
 *       missing twice), or when they are stated in the same words. Links are transitive. A follow-up shares its root's
 *       sources and wording on purpose (it probes the root's gap), so only different threads are linked by those two.
 * </ul>
 *
 * A system design thread is evaluated cumulatively, so only the findings of its last evaluated answer count: an earlier
 * answer's gap is either still in it or was closed later.
 */
public final class KnowledgeGapAnalyzer {

    /** Below this score a topic is weak (the adaptive engine's WEAK threshold). */
    public static final int WEAK_TOPIC_SCORE = 45;
    public static final int MAX_MISSED_CONCEPTS = 20;
    public static final int MAX_MISCONCEPTIONS = 20;
    public static final int MAX_REPEATED_MISTAKES = 10;

    private static final String ELLIPSIS = "...";

    /**
     * What one evaluated answer found.
     *
     * @param questionId the question
     * @param threadId   its thread's root question (its own id for a root question)
     * @param sequenceNo when it was asked
     * @param section    its section
     * @param topic      its catalog topic, null when it has none
     * @param concepts   the expected key points judged MISSING or PARTIAL, with the chunks the key point cites
     * @param mistakes   the factual errors
     */
    public record AnswerFindings(UUID questionId, UUID threadId, int sequenceNo, Section section, @Nullable GapTopic topic,
            List<ConceptGap> concepts, List<FactualError> mistakes) {

        public AnswerFindings {
            Require.nonNull(questionId, "questionId");
            Require.nonNull(threadId, "threadId");
            Require.nonNull(section, "section");
            concepts = Immutables.list(concepts);
            mistakes = Immutables.list(mistakes);
        }
    }

    /** An expected key point that was missed or partly covered, and the knowledge chunks it cites. */
    public record ConceptGap(String concept, KeyPointStatus status, List<UUID> sourceChunkIds) {

        public ConceptGap {
            Require.notBlank(concept, "concept", 500);
            Require.nonNull(status, "status");
            sourceChunkIds = Immutables.list(sourceChunkIds);
        }
    }

    private KnowledgeGapAnalyzer() {
    }

    /**
     * @param answers  the findings of every evaluated answer, in any order
     * @param topics   the report's topic scores
     * @param topicsByKey the display topic of every topic key that has a score
     */
    public static KnowledgeGapReport analyze(List<AnswerFindings> answers, List<ReportScores.TopicScore> topics, Map<String, GapTopic> topicsByKey) {
        List<AnswerFindings> counted = counted(answers);
        return new KnowledgeGapReport(missedConcepts(counted), misconceptions(counted), weakTopics(topics, topicsByKey), repeated(counted));
    }

    /** In asking order, with only the last evaluated answer of each system design thread. */
    private static List<AnswerFindings> counted(List<AnswerFindings> answers) {
        Map<UUID, AnswerFindings> lastOfDesignThread = new HashMap<>();
        for (AnswerFindings answer : answers) {
            if (answer.section() == Section.SYSTEM_DESIGN) {
                lastOfDesignThread.merge(answer.threadId(), answer, (a, b) -> a.sequenceNo() >= b.sequenceNo() ? a : b);
            }
        }
        return answers.stream()
                .filter(answer -> answer.section() != Section.SYSTEM_DESIGN || lastOfDesignThread.get(answer.threadId()) == answer)
                .sorted(Comparator.comparingInt(AnswerFindings::sequenceNo))
                .toList();
    }

    private static List<MissedConcept> missedConcepts(List<AnswerFindings> answers) {
        List<MissedConcept> missed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AnswerFindings answer : answers) {
            for (ConceptGap gap : answer.concepts()) {
                if (gap.status() != KeyPointStatus.COVERED && seen.add(normalise(gap.concept()))) {
                    missed.add(new MissedConcept(gap.concept(), gap.status(), answer.topic(), answer.questionId()));
                }
            }
        }
        return missed.size() <= MAX_MISSED_CONCEPTS ? missed : missed.subList(0, MAX_MISSED_CONCEPTS);
    }

    private static List<MisconceptionFinding> misconceptions(List<AnswerFindings> answers) {
        List<MisconceptionFinding> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AnswerFindings answer : answers) {
            for (FactualError error : answer.mistakes()) {
                if (seen.add(normalise(error.claim()))) {
                    found.add(new MisconceptionFinding(error.claim(), error.correction(), answer.topic(), answer.questionId()));
                }
            }
        }
        return found.size() <= MAX_MISCONCEPTIONS ? found : found.subList(0, MAX_MISCONCEPTIONS);
    }

    private static List<WeakTopic> weakTopics(List<ReportScores.TopicScore> topics, Map<String, GapTopic> topicsByKey) {
        return topics.stream()
                .filter(topic -> topic.score() < WEAK_TOPIC_SCORE && topicsByKey.containsKey(topic.topic()))
                .sorted(Comparator.comparingDouble(ReportScores.TopicScore::score).thenComparing(ReportScores.TopicScore::topic))
                .map(topic -> new WeakTopic(topicsByKey.get(topic.topic()), topic.score(), topic.evidenceCount()))
                .toList();
    }

    // ---- repeated mistakes ---------------------------------------------------------------------------------------

    /** One gap of one answer: a missed concept or a factual error. */
    private record Gap(AnswerFindings answer, @Nullable ConceptGap concept, @Nullable FactualError mistake) {

        List<UUID> chunks() {
            return concept != null ? concept.sourceChunkIds() : mistake.sourceChunkIds();
        }

        String description() {
            return concept != null ? concept.concept() : "\"" + mistake.claim() + "\" (correct: " + mistake.correction() + ")";
        }

        /** What the gap says, ignoring case and spacing: the concept missed, or the wrong claim made. */
        String wording() {
            return normalise(concept != null ? concept.concept() : mistake.claim());
        }
    }

    private static List<RepeatedMistake> repeated(List<AnswerFindings> answers) {
        List<Gap> gaps = new ArrayList<>();
        for (AnswerFindings answer : answers) {
            answer.concepts().stream().filter(gap -> gap.status() != KeyPointStatus.COVERED)
                    .forEach(concept -> gaps.add(new Gap(answer, concept, null)));
            answer.mistakes().forEach(mistake -> gaps.add(new Gap(answer, null, mistake)));
        }
        UnionFind links = new UnionFind(gaps.size());
        for (int i = 0; i < gaps.size(); i++) {
            Gap later = gaps.get(i);
            if (later.mistake() == null || later.mistake().repeats() == null) {
                continue;
            }
            for (int j = 0; j < gaps.size(); j++) {
                Gap earlier = gaps.get(j);
                if (earlier.mistake() != null && !earlier.answer().questionId().equals(later.answer().questionId())
                        && sameClaim(earlier.mistake().claim(), later.mistake().repeats())) {
                    links.union(i, j, RepeatedMistake.Basis.EVALUATOR_LINK);
                }
            }
        }
        Map<UUID, List<Integer>> byChunk = new LinkedHashMap<>();
        for (int i = 0; i < gaps.size(); i++) {
            for (UUID chunk : gaps.get(i).chunks()) {
                byChunk.computeIfAbsent(chunk, key -> new ArrayList<>()).add(i);
            }
        }
        byChunk.values().forEach(indexes -> linkAcrossThreads(gaps, indexes, links, RepeatedMistake.Basis.SHARED_SOURCE));

        Map<String, List<Integer>> byWording = new LinkedHashMap<>();
        for (int i = 0; i < gaps.size(); i++) {
            byWording.computeIfAbsent(gaps.get(i).wording(), key -> new ArrayList<>()).add(i);
        }
        byWording.values().forEach(indexes -> linkAcrossThreads(gaps, indexes, links, RepeatedMistake.Basis.SAME_WORDING));

        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < gaps.size(); i++) {
            groups.computeIfAbsent(links.find(i), root -> new ArrayList<>()).add(i);
        }
        List<RepeatedMistake> repeated = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> group : groups.entrySet()) {
            List<Gap> members = group.getValue().stream().map(gaps::get).toList();
            Set<UUID> questions = new LinkedHashSet<>();
            Set<GapTopic> topics = new LinkedHashSet<>();
            members.forEach(gap -> {
                questions.add(gap.answer().questionId());
                if (gap.answer().topic() != null) {
                    topics.add(gap.answer().topic());
                }
            });
            if (questions.size() < 2) {
                continue;
            }
            Gap first = members.stream().filter(gap -> gap.mistake() != null).findFirst().orElse(members.getFirst());
            RepeatedMistake.Kind kind = first.mistake() != null ? RepeatedMistake.Kind.MISCONCEPTION : RepeatedMistake.Kind.MISSED_CONCEPT;
            repeated.add(new RepeatedMistake(shorten(first.description(), 1200), kind, links.basis(group.getKey()),
                    List.copyOf(questions), List.copyOf(topics)));
        }
        return repeated.size() <= MAX_REPEATED_MISTAKES ? repeated : repeated.subList(0, MAX_REPEATED_MISTAKES);
    }

    private static void linkAcrossThreads(List<Gap> gaps, List<Integer> indexes, UnionFind links, RepeatedMistake.Basis basis) {
        for (int a = 0; a < indexes.size(); a++) {
            for (int b = a + 1; b < indexes.size(); b++) {
                if (!gaps.get(indexes.get(a)).answer().threadId().equals(gaps.get(indexes.get(b)).answer().threadId())) {
                    links.union(indexes.get(a), indexes.get(b), basis);
                }
            }
        }
    }

    /**
     * Whether a factual error's claim is the earlier mistake a link names. The interview memory keeps a claim of at most
     * 300 characters, cut with an ellipsis, so a cut claim matches every claim it is the beginning of.
     */
    static boolean sameClaim(String claim, String linked) {
        String full = normalise(claim);
        String named = normalise(linked);
        if (named.endsWith(ELLIPSIS)) {
            String prefix = named.substring(0, named.length() - ELLIPSIS.length()).strip();
            return !prefix.isEmpty() && full.startsWith(prefix);
        }
        return full.equals(named);
    }

    private static String normalise(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }

    /** Disjoint sets over the gaps, remembering which kinds of link joined each set. */
    private static final class UnionFind {

        private final int[] parent;
        private final Map<Integer, Set<RepeatedMistake.Basis>> basis = new HashMap<>();

        UnionFind(int size) {
            parent = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        int find(int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        void union(int a, int b, RepeatedMistake.Basis why) {
            int rootA = find(a);
            int rootB = find(b);
            Set<RepeatedMistake.Basis> joined = EnumSet.noneOf(RepeatedMistake.Basis.class);
            joined.addAll(basis.getOrDefault(rootA, Set.of()));
            joined.addAll(basis.getOrDefault(rootB, Set.of()));
            joined.add(why);
            int root = Math.min(rootA, rootB);
            parent[rootA] = root;
            parent[rootB] = root;
            basis.remove(rootA);
            basis.remove(rootB);
            basis.put(root, joined);
        }

        Set<RepeatedMistake.Basis> basis(int root) {
            return basis.getOrDefault(root, Set.of());
        }
    }
}
