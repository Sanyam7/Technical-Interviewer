package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.DifficultyBand;

/** The difficulty levels (1 to 5) at which a catalog topic can be asked: {@code topics.min_difficulty} to {@code max_difficulty}. */
public record TopicRange(int min, int max) {

    public TopicRange {
        Require.between(min, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "min");
        Require.between(max, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "max");
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must not exceed max (" + max + ")");
        }
    }

    /** The level nearest to {@code level} that the topic allows. */
    public int clamp(int level) {
        return Math.max(min, Math.min(max, level));
    }

    /** True when at least one level is allowed by both the topic and the band. */
    public boolean overlaps(DifficultyBand band) {
        return min <= band.max() && max >= band.min();
    }

    /** How many levels the topic's range is away from {@code level}; 0 when the level is inside the range. */
    public int distanceTo(int level) {
        return Math.abs(level - clamp(level));
    }
}
