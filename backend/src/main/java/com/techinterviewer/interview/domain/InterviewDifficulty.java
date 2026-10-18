package com.techinterviewer.interview.domain;

/**
 * Overall difficulty the candidate asks for, and the name of a difficulty level. The constants equal the
 * {@code ck_interview_configurations_difficulty} check.
 */
public enum InterviewDifficulty {
    EASY,
    MEDIUM,
    HARD;

    /**
     * The name of a level of the 1-to-5 scale the engine adapts on: 1 and 2 are EASY, 3 is MEDIUM, 4 and 5 are HARD. The
     * five levels let the engine move in smaller steps than three names; the names are what a person reads.
     */
    public static InterviewDifficulty ofLevel(int level) {
        if (level < DifficultyBand.LOWEST || level > DifficultyBand.HIGHEST) {
            throw new IllegalArgumentException("level must be between 1 and 5 but was " + level);
        }
        return level <= 2 ? EASY : level == 3 ? MEDIUM : HARD;
    }
}
