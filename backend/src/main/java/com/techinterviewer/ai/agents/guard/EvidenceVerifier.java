package com.techinterviewer.ai.agents.guard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that an evidence quote the evaluator returned really occurs in the candidate's answer (AI_ARCHITECTURE.md
 * section 2.3, step 2). This is the evaluator-hallucination signal: a key point "covered" with a quote the candidate
 * never wrote is downgraded to missing and the evaluation is flagged.
 *
 * <p>Matching is tolerant of what a faithful quote may change: case, punctuation, whitespace and Unicode forms are
 * ignored, and a quote whose words are at least 90 % present in the answer (counting repeats) passes, so an elided
 * filler word does not fail it. A quote of fewer than two words cannot show anything and fails.
 */
public final class EvidenceVerifier {

    static final double MIN_WORD_OVERLAP = 0.9;
    static final int MIN_QUOTE_WORDS = 2;

    private EvidenceVerifier() {
    }

    public static boolean occursIn(String answer, String quote) {
        List<String> quoteWords = TextNormalizer.words(quote);
        if (quoteWords.size() < MIN_QUOTE_WORDS) {
            return false;
        }
        String normalizedAnswer = TextNormalizer.normalize(answer);
        if ((" " + normalizedAnswer + " ").contains(" " + String.join(" ", quoteWords) + " ")) {
            return true;
        }
        Map<String, Integer> available = new HashMap<>();
        TextNormalizer.words(answer).forEach(word -> available.merge(word, 1, Integer::sum));
        int found = 0;
        for (String word : quoteWords) {
            Integer left = available.get(word);
            if (left != null && left > 0) {
                available.put(word, left - 1);
                found++;
            }
        }
        return found >= Math.ceil(MIN_WORD_OVERLAP * quoteWords.size());
    }
}
