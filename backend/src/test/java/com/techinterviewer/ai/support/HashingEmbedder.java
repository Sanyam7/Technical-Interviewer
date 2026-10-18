package com.techinterviewer.ai.support;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Test double of an embedding model: a deterministic bag-of-words vector (feature hashing). Each word, lower-cased,
 * crudely stemmed and without stop words, adds a signed weight to one of the dimensions its hash selects; the vector is
 * normalised. Texts that share words are similar, texts that share none are nearly orthogonal. It has no notion of
 * meaning (synonyms are unrelated), so tests that use it check the pipeline, not semantic quality; the live retrieval
 * evaluation measures that with a real model.
 */
public final class HashingEmbedder {

    /** Common English words, and the words of the task prefixes some models put before a text. */
    private static final Set<String> STOPWORDS = Set.of("a", "an", "the", "and", "or", "but", "if", "then", "else", "for", "with",
            "without", "that", "this", "these", "those", "is", "are", "was", "were", "be", "been", "being", "am", "do", "does",
            "did", "done", "have", "has", "had", "not", "no", "yes", "you", "your", "we", "our", "they", "their", "them", "it",
            "its", "he", "she", "his", "her", "who", "whom", "can", "could", "should", "would", "will", "shall", "may", "might",
            "must", "how", "what", "why", "when", "where", "which", "while", "from", "into", "onto", "of", "on", "in", "at", "to",
            "by", "as", "up", "down", "out", "over", "under", "about", "after", "before", "between", "through", "during", "one",
            "two", "use", "used", "using", "so", "than", "too", "very", "just", "only", "also", "more", "most", "less", "least",
            "much", "many", "some", "any", "all", "each", "every", "other", "another", "such", "same", "own", "long", "best",
            "make", "makes", "made", "get", "gets", "got", "there", "here", "now", "new", "old", "first", "last", "well", "way",
            "task", "search", "result", "query", "title", "none", "text");

    private HashingEmbedder() {
    }

    public static float[] embed(String text, int dimensions) {
        Map<String, Integer> counts = new HashMap<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9+#@]+")) {
            if (word.length() < 2 || STOPWORDS.contains(word)) {
                continue;
            }
            counts.merge(stem(word), 1, Integer::sum);
        }
        float[] vector = new float[dimensions];
        counts.forEach((word, count) -> {
            int hash = word.hashCode();
            int index = Math.floorMod(hash, dimensions);
            float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
            vector[index] += sign * (float) (1 + Math.log(count));
        });
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            vector[0] = 1;
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / Math.sqrt(norm));
        }
        return vector;
    }

    public static List<float[]> embedAll(List<String> texts, int dimensions) {
        return texts.stream().map(text -> embed(text, dimensions)).toList();
    }

    private static String stem(String word) {
        if (word.length() > 5 && word.endsWith("ing")) {
            return word.substring(0, word.length() - 3);
        }
        if (word.length() > 4 && word.endsWith("es")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
