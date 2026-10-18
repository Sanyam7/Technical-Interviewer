package com.techinterviewer.ai.agents.guard;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic checks of a generated question (AI_ARCHITECTURE.md section 2.2).
 *
 * <ul>
 *   <li><b>Answer leakage:</b> the question must not contain the text of one of its own expected key points (a
 *       candidate would read the answer in the question). Key points of fewer than four words are too short to judge.
 *   <li><b>Duplicates:</b> the question must not repeat one already asked. Until embeddings arrive with RAG (Phase 3)
 *       the check is lexical: the content words (stop words removed) of the shorter question must not be, for the
 *       most part ({@value #MAX_OVERLAP}), contained in the other. Overlap with the shorter one rather than a Jaccard
 *       index, because a restated question usually drops or adds a clause, which a Jaccard index forgives: a
 *       follow-up that repeated its parent minus two clauses scored 0.75 Jaccard but 0.96 overlap in a live run.
 * </ul>
 */
public final class QuestionChecks {

    static final int MIN_LEAK_WORDS = 4;
    static final double MAX_OVERLAP = 0.8;
    /** Below this many content words the overlap is not meaningful ("Explain volatile." vs "Explain it."). */
    static final int MIN_CONTENT_WORDS = 4;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "for", "with", "by", "at", "as", "from", "into",
            "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those", "you", "your", "we",
            "how", "what", "why", "when", "which", "who", "would", "could", "can", "do", "does", "did", "will", "should",
            "if", "than", "then", "there", "their", "they", "them", "about", "some", "any", "such", "like", "e", "g");

    private QuestionChecks() {
    }

    /** Whether {@code text} reveals {@code keyPoint}. */
    public static boolean leaks(String text, String keyPoint) {
        List<String> words = TextNormalizer.words(keyPoint);
        if (words.size() < MIN_LEAK_WORDS) {
            return false;
        }
        return (" " + TextNormalizer.normalize(text) + " ").contains(" " + String.join(" ", words) + " ");
    }

    /** Whether two questions are near-identical. */
    public static boolean nearDuplicate(String first, String second) {
        Set<String> a = contentWords(first);
        Set<String> b = contentWords(second);
        if (a.size() < MIN_CONTENT_WORDS || b.size() < MIN_CONTENT_WORDS) {
            return TextNormalizer.normalize(first).equals(TextNormalizer.normalize(second));
        }
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        return (double) common.size() / Math.min(a.size(), b.size()) >= MAX_OVERLAP;
    }

    private static Set<String> contentWords(String text) {
        Set<String> words = new HashSet<>(TextNormalizer.words(text));
        words.removeAll(STOP_WORDS);
        return words;
    }
}
