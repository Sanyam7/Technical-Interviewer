package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.TopicStat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

/**
 * Difficulty adaptation (AI_ARCHITECTURE.md sections 2.5 and 2.9). Each topic, and the candidate as a whole, keeps an
 * exponential moving average of its scores, {@code ema = 0.6 * score + 0.4 * previous}, rounded to two decimals like
 * every score. The level goes up one step when {@code ema >= 75} and the last score is at least 70, down one step when
 * {@code ema < 45}, and always stays inside the interview's band.
 *
 * <p>An evaluation in doubt (its confidence ended LOW: the server could not verify its evidence, or the evaluator was unsure) moves
 * no level: its score enters the averages like any other, but a single doubtful judgement neither raises nor lowers the next
 * question (AI_ARCHITECTURE.md section 2.15).
 *
 * <p>A topic returns at its own level. A topic asked for the first time, and a coding or design section, start at the
 * candidate's current level: each topic is asked only once or twice, so without the candidate-wide level a strong (or
 * weak) start would never carry over to the next topic.
 */
public final class DifficultyAdapter {

    /** Weight of the newest score in the moving average. */
    private static final BigDecimal NEW_SCORE_WEIGHT = new BigDecimal("0.6");
    private static final BigDecimal PREVIOUS_WEIGHT = new BigDecimal("0.4");

    public static final double RAISE_EMA_THRESHOLD = 75;
    public static final double RAISE_LAST_SCORE_THRESHOLD = 70;
    public static final double LOWER_EMA_THRESHOLD = 45;

    /**
     * The score a topic is seeded with when its first answer carries no evidence (the evaluation is pending): between
     * the lower and the upper threshold, so the topic neither rises nor falls on it.
     */
    public static final double NEUTRAL_SCORE = 50;

    private DifficultyAdapter() {
    }

    /** The new average after {@code score}; without a previous average (a first answer) the average is the score. */
    public static double ema(@Nullable Double previous, double score) {
        Require.between(score, 0, 100, "score");
        if (previous == null) {
            return score;
        }
        Require.between(previous, 0, 100, "previous");
        BigDecimal blended = NEW_SCORE_WEIGHT.multiply(BigDecimal.valueOf(score))
                .add(PREVIOUS_WEIGHT.multiply(BigDecimal.valueOf(previous)));
        return blended.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** The level for the topic's next question, given the average after the latest answer. */
    public static int nextLevel(int level, double ema, double lastScore, DifficultyBand band) {
        if (ema >= RAISE_EMA_THRESHOLD && lastScore >= RAISE_LAST_SCORE_THRESHOLD) {
            return band.clamp(level + 1);
        }
        if (ema < LOWER_EMA_THRESHOLD) {
            return band.clamp(level - 1);
        }
        return band.clamp(level);
    }

    /**
     * The level of a question that has no level of its own yet (a topic asked for the first time, a coding or design
     * problem): the candidate's current level, or the band's start before any technical answer was scored.
     */
    public static int startLevel(InterviewMemory memory, DifficultyBand band) {
        return band.clamp(memory.currentLevel(band.start()));
    }

    /** The topic's or the candidate's stat after a new score: average, level moved from {@code level}, the score as the last. */
    public static TopicStat next(@Nullable TopicStat previous, int level, double score, int asked, DifficultyBand band) {
        return next(previous, level, score, asked, band, true);
    }

    /**
     * As {@link #next(TopicStat, int, double, int, DifficultyBand)}; when the score comes from an evaluation in doubt
     * ({@code reliable} false) the average follows it but the level stays at {@code level}.
     */
    public static TopicStat next(@Nullable TopicStat previous, int level, double score, int asked, DifficultyBand band, boolean reliable) {
        double average = ema(previous == null ? null : previous.ema(), score);
        return new TopicStat(asked, average, reliable ? nextLevel(level, average, score, band) : band.clamp(level), score);
    }
}
