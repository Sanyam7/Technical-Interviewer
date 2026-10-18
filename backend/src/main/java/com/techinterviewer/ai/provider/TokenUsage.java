package com.techinterviewer.ai.provider;

import org.jspecify.annotations.Nullable;

/** Token counts of one call; null where the provider does not report a count. */
public record TokenUsage(@Nullable Integer input, @Nullable Integer output, @Nullable Integer thinking) {

    public static final TokenUsage UNKNOWN = new TokenUsage(null, null, null);

    public TokenUsage {
        requireNotNegative(input, "input");
        requireNotNegative(output, "output");
        requireNotNegative(thinking, "thinking");
    }

    private static void requireNotNegative(@Nullable Integer count, String name) {
        if (count != null && count < 0) {
            throw new IllegalArgumentException(name + " tokens must not be negative but were " + count);
        }
    }
}
