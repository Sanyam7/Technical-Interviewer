package com.techinterviewer.interview.engine;

import com.techinterviewer.interview.domain.TopicStat;
import org.jspecify.annotations.Nullable;

/**
 * How the candidate is doing on a topic (or overall), read from its moving average with the thresholds that also move
 * the difficulty ({@link DifficultyAdapter}): the same numbers decide "strong" and "ask harder", so the two never
 * disagree.
 */
public enum TopicPerformance {
    /** Nothing has been asked yet. */
    UNTESTED,
    /** Average below 45: the level goes down. */
    WEAK,
    /** In between: the level stays. */
    MIXED,
    /** Average of 75 or more: the level goes up when the last answer was good as well. */
    STRONG;

    public static TopicPerformance of(@Nullable TopicStat stat) {
        if (stat == null) {
            return UNTESTED;
        }
        if (stat.ema() >= DifficultyAdapter.RAISE_EMA_THRESHOLD) {
            return STRONG;
        }
        return stat.ema() < DifficultyAdapter.LOWER_EMA_THRESHOLD ? WEAK : MIXED;
    }
}
