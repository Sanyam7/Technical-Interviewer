package com.techinterviewer.ai.prompt;

import com.techinterviewer.ai.agents.guard.InjectionHeuristics;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a {@link PromptTemplate}. Every placeholder must get a value and every value must be used, so a template and
 * its agent cannot drift apart silently.
 *
 * <p><b>Untrusted text</b> is wrapped in markers that carry a random nonce, fresh for every rendering:
 * <pre>
 * [BEGIN UNTRUSTED candidate_answer 3f9c0a7e51d2b864]
 * ...the candidate's text...
 * [END UNTRUSTED candidate_answer 3f9c0a7e51d2b864]
 * </pre>
 * The system prompt tells the model that marked text is data, never instructions. The candidate cannot close the
 * block early because they cannot know the nonce, and any marker-like text they type is neutralised. This does not
 * make prompt injection impossible (nothing does); it makes it harder, and the evaluator also flags suspected
 * injection deterministically.
 *
 * <p>Every untrusted value also passes through {@link InjectionHeuristics#redact} here, in this one place: a sentence that
 * addresses the model (an order to ignore the rules, a request for the hidden criteria, a demand for a score) is replaced
 * by a marker before the model reads it, whichever agent built the prompt and whether the text came from a candidate, a
 * résumé, a model's earlier output or a retrieved document (SECURITY.md, AI-2).
 *
 * <p>Untrusted values are accepted in the user part only: the system part is the application's.
 */
public final class PromptRenderer {

    private static final Pattern MARKER_LIKE = Pattern.compile("(?i)\\[(\\s*)(BEGIN|END)(\\s+)UNTRUSTED");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Supplier<String> nonces;

    public PromptRenderer() {
        this(PromptRenderer::randomNonce);
    }

    /** For tests: a predictable nonce. */
    PromptRenderer(Supplier<String> nonces) {
        this.nonces = nonces;
    }

    /**
     * @throws IllegalArgumentException when a placeholder has no value, a value has no placeholder, or the system part
     *                                  would receive untrusted text
     */
    public RenderedPrompt render(PromptTemplate template, Map<String, PromptValue> values) {
        Set<String> wanted = new HashSet<>(template.systemPlaceholders());
        wanted.addAll(template.userPlaceholders());
        Set<String> missing = new HashSet<>(wanted);
        missing.removeAll(values.keySet());
        Set<String> unused = new HashSet<>(values.keySet());
        unused.removeAll(wanted);
        if (!missing.isEmpty() || !unused.isEmpty()) {
            throw new IllegalArgumentException("prompt " + template.id() + "/" + template.version()
                    + " placeholders without value: " + missing + "; values without placeholder: " + unused);
        }
        for (String name : template.systemPlaceholders()) {
            if (values.get(name) instanceof PromptValue.Untrusted) {
                throw new IllegalArgumentException("untrusted value '" + name + "' cannot go into the system part of prompt " + template.id());
            }
        }
        String nonce = nonces.get();
        return new RenderedPrompt(fill(template.system(), values, nonce), fill(template.user(), values, nonce), nonce);
    }

    private static String fill(String text, Map<String, PromptValue> values, String nonce) {
        Matcher matcher = PromptTemplate.PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = switch (values.get(matcher.group(1))) {
                case PromptValue.Trusted trusted -> trusted.text();
                case PromptValue.Untrusted untrusted -> delimit(untrusted, nonce);
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String delimit(PromptValue.Untrusted value, String nonce) {
        String body = MARKER_LIKE.matcher(InjectionHeuristics.redact(value.text())).replaceAll("($1$2$3UNTRUSTED");
        if (body.isBlank()) {
            body = "(empty)";
        }
        return "[BEGIN UNTRUSTED " + value.label() + " " + nonce + "]\n" + body.strip() + "\n[END UNTRUSTED " + value.label() + " " + nonce + "]";
    }

    private static String randomNonce() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * A filled prompt, ready for a provider.
     *
     * @param nonce the random value in this prompt's data markers; it exists nowhere else, so a reply that contains it was
     *              copied from the prompt (see {@code PromptEcho})
     */
    public record RenderedPrompt(String system, String user, String nonce) {
    }
}
