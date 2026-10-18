package com.techinterviewer.ai.prompt;

import com.techinterviewer.common.validation.Require;

/**
 * A value for a prompt placeholder. Text the application wrote itself is {@link Trusted}; anything a candidate wrote,
 * or that was derived from what a candidate wrote (an answer, a claim, a memory summary), is {@link Untrusted} and is
 * only ever inserted inside nonce-delimited data markers (see {@link PromptRenderer}). There is no way to insert
 * candidate text as plain prompt text.
 */
public sealed interface PromptValue {

    /** Application-written text, inserted as is. */
    static PromptValue trusted(String text) {
        return new Trusted(text);
    }

    /** Candidate-derived text, inserted between data markers named {@code label}. */
    static PromptValue untrusted(String label, String text) {
        return new Untrusted(label, text);
    }

    record Trusted(String text) implements PromptValue {
        public Trusted {
            Require.nonNull(text, "text");
        }
    }

    /**
     * @param label short name of the data, shown in the markers ({@code candidate_answer})
     * @param text  the data
     */
    record Untrusted(String label, String text) implements PromptValue {
        public Untrusted {
            Require.notBlank(label, "label", 40);
            if (!label.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("label must be lower-case snake case but was '" + label + "'");
            }
            Require.nonNull(text, "text");
        }
    }
}
