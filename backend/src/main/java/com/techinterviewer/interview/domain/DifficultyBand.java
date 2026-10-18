package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;

/**
 * The difficulty levels (1 to 5) an interview may use: where it starts and how far the adapter may move.
 * Invariant: {@code min <= start <= max}.
 */
public record DifficultyBand(int start, int min, int max) {

    public static final int LOWEST = 1;
    public static final int HIGHEST = 5;

    public DifficultyBand {
        Require.between(min, LOWEST, HIGHEST, "min");
        Require.between(max, LOWEST, HIGHEST, "max");
        Require.between(start, LOWEST, HIGHEST, "start");
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must not exceed max (" + max + ")");
        }
        if (start < min || start > max) {
            throw new IllegalArgumentException("start (" + start + ") must lie within [" + min + ", " + max + "]");
        }
    }

    /** The level nearest to {@code level} that the band allows. */
    public int clamp(int level) {
        return Math.max(min, Math.min(max, level));
    }
}
