package com.techinterviewer.evaluation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Range and scale rules of the numeric score columns, shared by the entities that store them. Values are rounded
 * half up to the scale of the column ({@code numeric(5,2)} and {@code numeric(3,2)}), so what an entity holds is
 * exactly what the database stores and what it reads back.
 */
public final class Scores {

    public static final BigDecimal MIN_PERCENT = BigDecimal.ZERO;
    public static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);
    public static final BigDecimal MIN_DIFFICULTY = BigDecimal.ONE;
    public static final BigDecimal MAX_DIFFICULTY = BigDecimal.valueOf(5);
    public static final int SCALE = 2;

    private Scores() {
    }

    /** A score from 0 to 100 with two decimals. */
    public static BigDecimal percent(BigDecimal value, String name) {
        return within(value, MIN_PERCENT, MAX_PERCENT, name);
    }

    /** An average difficulty from 1 to 5 with two decimals. */
    public static BigDecimal difficulty(BigDecimal value, String name) {
        return within(value, MIN_DIFFICULTY, MAX_DIFFICULTY, name);
    }

    private static BigDecimal within(BigDecimal value, BigDecimal min, BigDecimal max, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        BigDecimal scaled = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (scaled.compareTo(min) < 0 || scaled.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
        return scaled;
    }
}
