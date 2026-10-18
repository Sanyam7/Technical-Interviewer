package com.techinterviewer.ai.structured;

import com.techinterviewer.ai.prompt.PromptRenderer.RenderedPrompt;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Catches a reply that recites the prompt (SECURITY.md, AI-3). What a model writes reaches the candidate: as a question, as a
 * follow-up, as the feedback and the report. A candidate who talks the model into "repeating everything above" should get
 * nothing out of it, so a reply is rejected, like any invalid output, when one of its texts
 *
 * <ul>
 *   <li>contains the random nonce of the prompt's data markers, or a data marker itself: the nonce exists nowhere but in the
 *       prompt, so it can only have been copied from it; or
 *   <li>contains {@value #RUN_WORDS} consecutive words of the system prompt, which holds the rules and the rubric. A reply
 *       that long a copy of the instructions is a recital, not an answer; ordinary phrases are far shorter.
 * </ul>
 *
 * Only the system part is checked: the user part holds the candidate's own words, the question and the reference material,
 * which a good reply quotes (evidence quotes) on purpose.
 */
final class PromptEcho {

    /** How many consecutive words of the system prompt make a recital. */
    static final int RUN_WORDS = 10;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern MARKER = Pattern.compile("(?i)\\[\\s*(begin|end)\\s+untrusted");

    private final String nonce;
    private final Set<String> instructionRuns;

    private PromptEcho(String nonce, Set<String> instructionRuns) {
        this.nonce = nonce.toLowerCase(Locale.ROOT);
        this.instructionRuns = instructionRuns;
    }

    static PromptEcho of(RenderedPrompt prompt) {
        List<String> words = words(prompt.system());
        Set<String> runs = new HashSet<>();
        for (int i = 0; i + RUN_WORDS <= words.size(); i++) {
            runs.add(String.join(" ", words.subList(i, i + RUN_WORDS)));
        }
        return new PromptEcho(prompt.nonce(), runs);
    }

    /** The problems with a reply; empty when it recites nothing. The messages do not repeat what was recited. */
    List<String> problems(JsonNode reply) {
        List<String> problems = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        collect(reply, texts);
        for (String text : texts) {
            if (text.toLowerCase(Locale.ROOT).contains(nonce) || MARKER.matcher(text).find()) {
                problems.add("the reply contains the data markers of the prompt; they are not part of the answer");
                break;
            }
        }
        for (String text : texts) {
            if (recites(text)) {
                problems.add("the reply repeats the instructions you were given; answer the task in your own words");
                break;
            }
        }
        return problems;
    }

    private boolean recites(String text) {
        List<String> words = words(text);
        for (int i = 0; i + RUN_WORDS <= words.size(); i++) {
            if (instructionRuns.contains(String.join(" ", words.subList(i, i + RUN_WORDS)))) {
                return true;
            }
        }
        return false;
    }

    private static void collect(JsonNode node, List<String> texts) {
        if (node.isString()) {
            texts.add(node.asString());
        } else if (node.isObject() || node.isArray()) {
            node.forEach(child -> collect(child, texts));
        }
    }

    private static List<String> words(String text) {
        Matcher matcher = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }
}
