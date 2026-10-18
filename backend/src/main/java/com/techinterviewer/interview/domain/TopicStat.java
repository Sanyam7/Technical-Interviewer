package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;

/**
 * What the interview has learned about one topic.
 *
 * @param asked     questions asked on the topic so far
 * @param ema       exponential moving average of the scores, 0 to 100 ({@code 0.6 * score + 0.4 * previous})
 * @param level     current difficulty level for the topic, 1 to 5
 * @param lastScore score of the latest answer, 0 to 100
 */
public record TopicStat(int asked, double ema, int level, double lastScore) {

    public TopicStat {
        Require.atLeast(asked, 0, "asked");
        Require.between(ema, 0, 100, "ema");
        Require.between(level, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "level");
        Require.between(lastScore, 0, 100, "lastScore");
    }
}
