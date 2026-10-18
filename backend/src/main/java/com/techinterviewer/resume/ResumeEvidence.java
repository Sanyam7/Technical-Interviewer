package com.techinterviewer.resume;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether words were really copied from a résumé. Stricter than the evaluator's quote check (which accepts 90 % of the words
 * found anywhere in a short answer): in a résumé of several thousand words almost any sentence could be assembled from words
 * that occur somewhere, so here the words must occur <em>in sequence</em>. Case, punctuation, white space, line breaks and
 * Unicode forms are ignored, so a faithful copy of a line a PDF broke in two still matches.
 */
public final class ResumeEvidence {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private final List<String> words;

    public ResumeEvidence(String text) {
        this.words = words(text);
    }

    /** Whether {@code quote} has at least {@code minWords} words and they occur in sequence in the text. */
    public boolean contains(String quote, int minWords) {
        List<String> needle = words(quote);
        return needle.size() >= minWords && Collections.indexOfSubList(words, needle) >= 0;
    }

    /** Whether {@code part} (at least one word) occurs in sequence inside {@code whole}. */
    public static boolean within(String whole, String part) {
        List<String> needle = words(part);
        return !needle.isEmpty() && Collections.indexOfSubList(words(whole), needle) >= 0;
    }

    public static List<String> words(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(folded);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }
}
