package com.techinterviewer.evaluation.service;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The give-up policy for answers that cannot be evaluated (AI_ARCHITECTURE.md section 10.6).
 *
 * @param maxAttempts      evaluation attempts per answer, the first one included, before it is marked UNASSESSABLE
 * @param backoffInitial   wait before the second attempt; doubled for every further one
 * @param backoffMax       longest wait between two attempts. A caller waits for a due attempt only when it is at most this far
 *                         away; otherwise it reports the evaluation as still in progress
 * @param attemptLease     how long an attempt in progress blocks other attempts at the same answer; after it a crashed attempt
 *                         (a process that died) is counted as failed. Must exceed the longest time the evaluator can take
 * @param minCoverage      share (0 to 1) of the intended assessment weight that must be assessable for a report to carry a score
 *                         and a recommendation; below it the report is INCONCLUSIVE. Exactly this share is enough
 */
@ConfigurationProperties("app.evaluation")
@Validated
public record EvaluationProperties(
        @Min(1) @Max(10) @DefaultValue("3") int maxAttempts,
        @NotNull @DurationMin(millis = 0) @DurationMax(minutes = 5) @DefaultValue("2s") Duration backoffInitial,
        @NotNull @DurationMin(millis = 0) @DurationMax(minutes = 5) @DefaultValue("10s") Duration backoffMax,
        @NotNull @DurationMin(seconds = 1) @DurationMax(minutes = 10) @DefaultValue("90s") Duration attemptLease,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.70") BigDecimal minCoverage) {

    /** The wait after attempt number {@code attempt} (from 1) failed, before the next one: doubling, never above {@link #backoffMax}. */
    public Duration backoffAfter(int attempt) {
        Duration delay = backoffInitial;
        for (int i = 1; i < attempt && delay.compareTo(backoffMax) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(backoffMax) > 0 ? backoffMax : delay;
    }
}
