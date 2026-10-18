package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.KnowledgeGapAnalyzer.AnswerFindings;
import com.techinterviewer.evaluation.KnowledgeGapAnalyzer.ConceptGap;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport.GapTopic;
import com.techinterviewer.evaluation.domain.KnowledgeGapReport.RepeatedMistake;
import com.techinterviewer.interview.domain.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Knowledge gaps over a whole interview: what was missed, what was wrong, what was weak, and what happened more than once. */
class KnowledgeGapAnalyzerTest {

    private static final GapTopic CONCURRENCY = new GapTopic("java/concurrency", "Java / Concurrency");
    private static final GapTopic COLLECTIONS = new GapTopic("java/collections", "Java / Collections");
    private static final GapTopic INDEXING = new GapTopic("postgresql/indexing", "PostgreSQL / Indexing");
    private static final UUID JMM_CHUNK = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HASHMAP_CHUNK = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final List<AnswerFindings> answers = new ArrayList<>();
    private int sequence = 1;

    private AnswerFindings answer(GapTopic topic, List<ConceptGap> concepts, List<FactualError> mistakes) {
        UUID id = UUID.randomUUID();
        return answer(id, id, Section.QUESTIONING, topic, concepts, mistakes);
    }

    private AnswerFindings answer(UUID id, UUID thread, Section section, GapTopic topic, List<ConceptGap> concepts, List<FactualError> mistakes) {
        AnswerFindings findings = new AnswerFindings(id, thread, ++sequence, section, topic, concepts, mistakes);
        answers.add(findings);
        return findings;
    }

    private static ConceptGap missing(String concept, UUID... chunks) {
        return new ConceptGap(concept, KeyPointStatus.MISSING, List.of(chunks));
    }

    private static FactualError error(String claim, String correction, UUID... chunks) {
        return new FactualError(claim, correction, List.of(chunks));
    }

    private KnowledgeGapReport analyze() {
        return KnowledgeGapAnalyzer.analyze(answers, List.of(), Map.of());
    }

    @Test
    void missedConceptsAndMisconceptionsAreListedInTheOrderTheyWereAskedWithoutDuplicates() {
        AnswerFindings first = answer(CONCURRENCY,
                List.of(missing("count++ is not atomic"), new ConceptGap("volatile gives visibility", KeyPointStatus.PARTIAL, List.of()),
                        new ConceptGap("covered points are not gaps", KeyPointStatus.COVERED, List.of())),
                List.of(error("volatile makes count++ atomic", "it only gives visibility")));
        AnswerFindings second = answer(COLLECTIONS, List.of(missing("Count++ is NOT atomic")),
                List.of(error("Volatile makes count++  atomic", "same mistake, same words")));

        KnowledgeGapReport gaps = analyze();

        assertThat(gaps.missedConcepts()).extracting(KnowledgeGapReport.MissedConcept::concept)
                .containsExactly("count++ is not atomic", "volatile gives visibility");
        assertThat(gaps.missedConcepts().getFirst()).isEqualTo(
                new KnowledgeGapReport.MissedConcept("count++ is not atomic", KeyPointStatus.MISSING, CONCURRENCY, first.questionId()));
        assertThat(gaps.misconceptions()).singleElement().isEqualTo(new KnowledgeGapReport.MisconceptionFinding(
                "volatile makes count++ atomic", "it only gives visibility", CONCURRENCY, first.questionId()));
        assertThat(gaps.repeatedMistakes()).as("the same words in two questions").containsExactly(
                new RepeatedMistake("count++ is not atomic", RepeatedMistake.Kind.MISSED_CONCEPT, Set.of(RepeatedMistake.Basis.SAME_WORDING),
                        List.of(first.questionId(), second.questionId()), List.of(CONCURRENCY, COLLECTIONS)),
                new RepeatedMistake("\"volatile makes count++ atomic\" (correct: it only gives visibility)", RepeatedMistake.Kind.MISCONCEPTION,
                        Set.of(RepeatedMistake.Basis.SAME_WORDING), List.of(first.questionId(), second.questionId()), List.of(CONCURRENCY, COLLECTIONS)));
    }

    @Test
    void weakTopicsAreTheTopicsBelowFortyFiveWeakestFirst() {
        List<ReportScores.TopicScore> scores = List.of(
                new ReportScores.TopicScore("java/concurrency", 44.99, 2, 3.0),
                new ReportScores.TopicScore("java/collections", 45.0, 1, 2.0),
                new ReportScores.TopicScore("postgresql/indexing", 20.0, 1, 3.0));

        KnowledgeGapReport gaps = KnowledgeGapAnalyzer.analyze(List.of(), scores,
                Map.of(CONCURRENCY.key(), CONCURRENCY, COLLECTIONS.key(), COLLECTIONS, INDEXING.key(), INDEXING));

        assertThat(gaps.weakTopics()).containsExactly(
                new KnowledgeGapReport.WeakTopic(INDEXING, 20.0, 1),
                new KnowledgeGapReport.WeakTopic(CONCURRENCY, 44.99, 2));
    }

    @Test
    void aMistakeTheEvaluatorLinkedToAnEarlierOneIsARepeatedMisconception() {
        AnswerFindings first = answer(CONCURRENCY, List.of(), List.of(error("HashMap is thread-safe", "it is not; use ConcurrentHashMap")));
        answer(INDEXING, List.of(), List.of(error("an index makes every query faster", "not for low selectivity")));
        AnswerFindings third = answer(COLLECTIONS, List.of(),
                List.of(new FactualError("a HashMap can be shared freely between threads", "it cannot", List.of(), "HashMap is thread-safe")));

        assertThat(analyze().repeatedMistakes()).singleElement().isEqualTo(new RepeatedMistake(
                "\"HashMap is thread-safe\" (correct: it is not; use ConcurrentHashMap)", RepeatedMistake.Kind.MISCONCEPTION,
                Set.of(RepeatedMistake.Basis.EVALUATOR_LINK), List.of(first.questionId(), third.questionId()), List.of(CONCURRENCY, COLLECTIONS)));
    }

    @Test
    void gapsOfDifferentQuestionsThatCiteTheSameKnowledgeAreOneRepeatedGap() {
        AnswerFindings first = answer(CONCURRENCY, List.of(missing("happens-before through volatile", JMM_CHUNK)), List.of());
        answer(INDEXING, List.of(missing("partial indexes")), List.of());
        AnswerFindings third = answer(COLLECTIONS, List.of(), List.of(error("synchronized is only about mutual exclusion",
                "it also publishes writes (happens-before)", JMM_CHUNK)));

        RepeatedMistake repeated = analyze().repeatedMistakes().getFirst();

        assertThat(repeated.kind()).as("one occurrence is a wrong belief").isEqualTo(RepeatedMistake.Kind.MISCONCEPTION);
        assertThat(repeated.basis()).containsExactly(RepeatedMistake.Basis.SHARED_SOURCE);
        assertThat(repeated.questionIds()).containsExactly(first.questionId(), third.questionId());
        assertThat(analyze().repeatedMistakes()).hasSize(1);
    }

    @Test
    void conceptsMissedTwiceAreARepeatedMissedConcept() {
        answer(CONCURRENCY, List.of(missing("HashMap resize under contention", HASHMAP_CHUNK)), List.of());
        answer(COLLECTIONS, List.of(missing("why HashMap is unsafe to share", HASHMAP_CHUNK)), List.of());

        RepeatedMistake repeated = analyze().repeatedMistakes().getFirst();

        assertThat(repeated.kind()).isEqualTo(RepeatedMistake.Kind.MISSED_CONCEPT);
        assertThat(repeated.description()).isEqualTo("HashMap resize under contention");
    }

    @Test
    void linksAreTransitiveAndKeepEveryBasis() {
        AnswerFindings a = answer(CONCURRENCY, List.of(missing("the Java memory model", JMM_CHUNK)), List.of());
        AnswerFindings b = answer(COLLECTIONS, List.of(), List.of(error("volatile locks the field", "it does not lock", JMM_CHUNK)));
        AnswerFindings c = answer(INDEXING, List.of(), List.of(new FactualError("volatile is a lock", "no", List.of(), "volatile locks the field")));

        RepeatedMistake repeated = analyze().repeatedMistakes().getFirst();

        assertThat(repeated.questionIds()).containsExactly(a.questionId(), b.questionId(), c.questionId());
        assertThat(repeated.basis()).containsExactlyInAnyOrder(RepeatedMistake.Basis.SHARED_SOURCE, RepeatedMistake.Basis.EVALUATOR_LINK);
        assertThat(repeated.description()).as("described by its first wrong belief").startsWith("\"volatile locks the field\"");
    }

    @Test
    void aFollowUpThatMissesWhatItsRootMissedIsNotARepeat() {
        UUID root = UUID.randomUUID();
        answer(root, root, Section.QUESTIONING, CONCURRENCY, List.of(missing("count++ is a read-modify-write", JMM_CHUNK)), List.of());
        answer(UUID.randomUUID(), root, Section.QUESTIONING, CONCURRENCY, List.of(missing("lost updates", JMM_CHUNK)), List.of());

        assertThat(analyze().repeatedMistakes()).as("the probe of a gap shares its sources").isEmpty();
    }

    @Test
    void anEvaluatorLinkToTheSameQuestionOrToNothingKnownIsIgnored() {
        answer(CONCURRENCY, List.of(), List.of(
                error("volatile is atomic", "it is not"),
                new FactualError("volatile is atomic again", "no", List.of(), "volatile is atomic")));
        answer(COLLECTIONS, List.of(), List.of(new FactualError("TreeMap is a hash table", "it is a red-black tree", List.of(), "a claim never recorded")));

        assertThat(analyze().repeatedMistakes()).isEmpty();
    }

    @Test
    void onlyTheLastEvaluatedAnswerOfADesignThreadCountsBecauseItsEvaluationIsCumulative() {
        UUID root = UUID.randomUUID();
        answer(root, root, Section.SYSTEM_DESIGN, null, List.of(missing("idempotent consumers"), missing("back-pressure")), List.of());
        AnswerFindings last = answer(UUID.randomUUID(), root, Section.SYSTEM_DESIGN, null, List.of(missing("back-pressure")), List.of());

        assertThat(analyze().missedConcepts()).singleElement().satisfies(gap -> {
            assertThat(gap.concept()).isEqualTo("back-pressure");
            assertThat(gap.questionId()).isEqualTo(last.questionId());
            assertThat(gap.topic()).isNull();
        });
    }

    @Test
    void aClaimTheMemoryCutShortStillMatchesTheFullClaim() {
        String full = "volatile " + "x".repeat(400);
        String cut = full.substring(0, 297).strip() + "...";

        assertThat(KnowledgeGapAnalyzer.sameClaim(full, cut)).isTrue();
        assertThat(KnowledgeGapAnalyzer.sameClaim("  Volatile   IS atomic ", "volatile is atomic")).isTrue();
        assertThat(KnowledgeGapAnalyzer.sameClaim("volatile is atomic and fast", "volatile is atomic")).as("not cut: whole claim").isFalse();
        assertThat(KnowledgeGapAnalyzer.sameClaim("anything", "...")).isFalse();
    }

    @Test
    void theListsAreBounded() {
        for (int i = 0; i < 30; i++) {
            answer(CONCURRENCY, List.of(missing("concept " + i, UUID.randomUUID())), List.of(error("claim " + i, "fix " + i)));
        }

        KnowledgeGapReport gaps = analyze();

        assertThat(gaps.missedConcepts()).hasSize(KnowledgeGapAnalyzer.MAX_MISSED_CONCEPTS);
        assertThat(gaps.misconceptions()).hasSize(KnowledgeGapAnalyzer.MAX_MISCONCEPTIONS);
    }
}
