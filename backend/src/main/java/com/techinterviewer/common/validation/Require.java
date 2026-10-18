package com.techinterviewer.common.validation;

import org.jspecify.annotations.Nullable;

/**
 * Argument checks for the compact constructors of value records, so an impossible value (a rating of 9,
 * a blank id) can never exist, whether it was built in code or read from JSON. Every method returns its
 * argument so it can be used in an assignment, and fails with an {@link IllegalArgumentException} that
 * names the offending field.
 */
public final class Require {

    private Require() {
    }

    public static <T> T nonNull(@Nullable T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    public static String notBlank(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return noControlCharacters(value, name);
    }

    public static String notBlank(@Nullable String value, String name, int maxLength) {
        return maxLength(notBlank(value, name), name, maxLength);
    }

    /** A text that may be absent but, when present, is not blank and not longer than {@code maxLength}. */
    public static @Nullable String nullOrNotBlank(@Nullable String value, String name, int maxLength) {
        return value == null ? null : notBlank(value, name, maxLength);
    }

    /** A text that must be present but may be empty. */
    public static String text(@Nullable String value, String name, int maxLength) {
        return maxLength(noControlCharacters(nonNull(value, name), name), name, maxLength);
    }

    /**
     * Rejects control characters other than tab, line feed and carriage return. U+0000 in particular cannot be
     * stored by PostgreSQL, so a model result containing it must fail construction (and be handled as an unusable
     * result) rather than fail later at the database.
     */
    public static String noControlCharacters(String value, String name) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r') {
                throw new IllegalArgumentException(name + " must not contain control characters");
            }
        }
        return value;
    }

    public static String maxLength(String value, String name, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not be longer than " + maxLength + " characters");
        }
        return value;
    }

    public static int between(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
        return value;
    }

    /** NaN and infinities are rejected. */
    public static double between(double value, double min, double max, String name) {
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
        return value;
    }

    public static int atLeast(int value, int min, String name) {
        if (value < min) {
            throw new IllegalArgumentException(name + " must be at least " + min + " but was " + value);
        }
        return value;
    }
}
