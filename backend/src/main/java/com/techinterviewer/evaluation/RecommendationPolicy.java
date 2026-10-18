package com.techinterviewer.evaluation;

import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Recommendation;
import org.jspecify.annotations.Nullable;

/**
 * The hiring-style band of an interview, from the overall score (AI_ARCHITECTURE.md section 2.8):
 * <ul>
 *   <li>80 and above: STRONG
 *   <li>65 up to (not including) 80: PROFICIENT
 *   <li>45 up to (not including) 65: DEVELOPING
 *   <li>below 45: NOT_YET
 * </ul>
 * No band is given when there is no overall score (insufficient evidence) or when the overall confidence is LOW: a
 * recommendation on a handful of answers would be false precision.
 *
 * <p>Weak fundamentals cap the band (AI_ARCHITECTURE.md section 2.15): when the FUNDAMENTALS area scored below
 * {@value #WEAK_FUNDAMENTALS} over at least {@value #MIN_FUNDAMENTALS_EVIDENCE} answers, the band is at most DEVELOPING, however well
 * the practical or project answers went. The overall score itself is not changed: it stays the weighted mean of every answer, and
 * the report says why the band is lower than the score alone gives.
 */
public final class RecommendationPolicy {

    public static final double STRONG_MIN = 80;
    public static final double PROFICIENT_MIN = 65;
    public static final double DEVELOPING_MIN = 45;
    public static final double WEAK_FUNDAMENTALS = 45;
    public static final int MIN_FUNDAMENTALS_EVIDENCE = 2;

    /**
     * A band after the fundamentals cap.
     *
     * @param recommendation the band, possibly capped
     * @param capReason      why it was capped; null when it was not
     */
    public record Capped(@Nullable Recommendation recommendation, @Nullable String capReason) {
    }

    private RecommendationPolicy() {
    }

    public static @Nullable Recommendation recommend(@Nullable Double overallScore, Confidence overallConfidence) {
        if (overallScore == null || overallConfidence == Confidence.LOW) {
            return null;
        }
        if (overallScore >= STRONG_MIN) {
            return Recommendation.STRONG;
        }
        if (overallScore >= PROFICIENT_MIN) {
            return Recommendation.PROFICIENT;
        }
        return overallScore >= DEVELOPING_MIN ? Recommendation.DEVELOPING : Recommendation.NOT_YET;
    }

    public static @Nullable Recommendation recommend(ReportScores scores) {
        return recommend(scores.overallScore(), scores.overallConfidence());
    }

    /** The band of {@code scores}, capped at DEVELOPING when the fundamentals are weak (see the class comment). */
    public static Capped recommendWithFundamentals(ReportScores scores) {
        Recommendation band = recommend(scores);
        ReportScores.AreaResult fundamentals = scores.area(AssessmentArea.FUNDAMENTALS).orElse(null);
        boolean weak = fundamentals != null && fundamentals.score() != null
                && fundamentals.evidenceCount() >= MIN_FUNDAMENTALS_EVIDENCE && fundamentals.score() < WEAK_FUNDAMENTALS;
        if (!weak || band == null || band.compareTo(Recommendation.DEVELOPING) >= 0) {
            return new Capped(band, null);
        }
        String score = java.math.BigDecimal.valueOf(fundamentals.score()).stripTrailingZeros().toPlainString();
        return new Capped(Recommendation.DEVELOPING, "Capped at Developing: the fundamentals scored " + score + " over "
                + fundamentals.evidenceCount() + " answers, below " + (int) WEAK_FUNDAMENTALS
                + ". Strong practical or project answers do not make up for weak fundamentals; the overall score alone would give "
                + (band == Recommendation.STRONG ? "Strong" : "Proficient") + ".");
    }
}
