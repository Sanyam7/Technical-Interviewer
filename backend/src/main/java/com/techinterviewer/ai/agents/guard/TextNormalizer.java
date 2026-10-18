package com.techinterviewer.ai.agents.guard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normal form used by the deterministic guards: Unicode compatibility form (so full-width or styled letters compare
 * equal to plain ones), lower case, words of letters and digits only.
 */
final class TextNormalizer {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private TextNormalizer() {
    }

    static String normalize(String text) {
        return String.join(" ", words(text));
    }

    static List<String> words(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(folded);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }
}
