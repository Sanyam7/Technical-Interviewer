package com.techinterviewer.interview.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether an answer repeats one the candidate already gave in this interview: at least {@value #MIN_CONTAINMENT} of its
 * word trigrams appear in one earlier answer. Trigrams rather than single words, because a follow-up answer naturally
 * reuses the vocabulary of the answer it follows; what it should not do is say the same sentences again. Containment of
 * the new answer rather than a symmetric index, because a pasted answer with a line added is still a repeat. Answers of
 * fewer than {@value #MIN_TRIGRAMS} trigrams (about seven words) are never called repeats: "I don't know" twice is not.
 *
 * <p>Deterministic and local: no model decides it, and it needs only the texts the interview already stores.
 */
final class AnswerRepetition {

    static final double MIN_CONTAINMENT = 0.8;
    static final int MIN_TRIGRAMS = 5;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private AnswerRepetition() {
    }

    static boolean repeats(String answer, List<String> earlierAnswers) {
        Set<String> trigrams = trigrams(answer);
        if (trigrams.size() < MIN_TRIGRAMS) {
            return false;
        }
        for (String earlier : earlierAnswers) {
            Set<String> common = trigrams(earlier);
            common.retainAll(trigrams);
            if ((double) common.size() / trigrams.size() >= MIN_CONTAINMENT) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> trigrams(String text) {
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        List<String> words = matcher.results().map(match -> match.group()).toList();
        Set<String> trigrams = new HashSet<>();
        for (int i = 0; i + 2 < words.size(); i++) {
            trigrams.add(words.get(i) + ' ' + words.get(i + 1) + ' ' + words.get(i + 2));
        }
        return trigrams;
    }
}
