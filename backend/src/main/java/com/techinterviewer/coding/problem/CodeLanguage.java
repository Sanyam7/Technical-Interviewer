package com.techinterviewer.coding.problem;

import java.util.Arrays;
import java.util.Optional;

/**
 * A language a coding answer can be written in. The executable ones have a test harness and a sandbox image; the others
 * are reviewed by the AI evaluator only, and their correctness is then not confirmed by tests.
 */
public enum CodeLanguage {
    JAVA("java", true),
    JAVASCRIPT("javascript", true),
    TYPESCRIPT("typescript", true),
    PYTHON("python", false),
    GO("go", false);

    private final String code;
    private final boolean executable;

    CodeLanguage(String code, boolean executable) {
        this.code = code;
        this.executable = executable;
    }

    /** The catalog code, for example {@code java}. */
    public String code() {
        return code;
    }

    /** Whether tests can be run for this language (when execution is enabled). */
    public boolean executable() {
        return executable;
    }

    public static Optional<CodeLanguage> fromCode(String code) {
        return Arrays.stream(values()).filter(language -> language.code.equals(code)).findFirst();
    }
}
