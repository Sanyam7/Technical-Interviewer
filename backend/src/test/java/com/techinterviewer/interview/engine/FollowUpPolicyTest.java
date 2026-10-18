package com.techinterviewer.interview.engine;

import static com.techinterviewer.evaluation.domain.KeyPointStatus.COVERED;
import static com.techinterviewer.evaluation.domain.KeyPointStatus.MISSING;
import static com.techinterviewer.evaluation.domain.KeyPointStatus.PARTIAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.engine.FollowUpDecision.Reason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FollowUpPolicyTest {

    private static final Duration PLENTY = Duration.ofMinutes(10);

    /** A root question (depth 0, max 2) with ten minutes left, no claims, and the given score and key points. */
    private static Builder base(Double score) {
        return new Builder(score);
    }


    /** Test data builder: a root question, 10 minutes left, no key points, no claims, claim probe allowed, no deeper level. */
    private static final class Builder {

        private final Double score;
        private int depth;
        private int max = 2;
        private Duration timeLeft = PLENTY;
        private List<KeyPointResult> keyPoints = List.of();
        private List<String> claims = List.of();
        private boolean claimProbeAllowed = true;
        private boolean deeper;
        private boolean repeated;
        private boolean closing;
        private Duration reserved = Duration.ZERO;

        Builder(Double score) {
            this.score = score;
        }

        Builder depth(int depth, int max) {
            this.depth = depth;
            this.max = max;
            return this;
        }

        Builder timeLeft(Duration timeLeft) {
            this.timeLeft = timeLeft;
            return this;
        }

        Builder keyPoints(List<KeyPointResult> keyPoints) {
            this.keyPoints = keyPoints;
            return this;
        }

        Builder claims(String... claims) {
            this.claims = List.of(claims);
            return this;
        }

        Builder claimProbeAllowed(boolean allowed) {
            this.claimProbeAllowed = allowed;
            return this;
        }

        Builder deeper() {
            this.deeper = true;
            return this;
        }

        Builder repeated() {
            this.repeated = true;
            return this;
        }

        Builder closing() {
            this.closing = true;
            return this;
        }

        Builder reserved(Duration reserved) {
            this.reserved = reserved;
            return this;
        }

        FollowUpInput build() {
            return new FollowUpInput(score, depth, max, timeLeft, keyPoints, claims, claimProbeAllowed, deeper, repeated, closing, reserved);
        }
    }

    private static List<KeyPointResult> keyPoints(Object... idsAndStatuses) {
        List<KeyPointResult> results = new ArrayList<>();
        for (int i = 0; i < idsAndStatuses.length; i += 2) {
            results.add(new KeyPointResult((String) idsAndStatuses[i], (KeyPointStatus) idsAndStatuses[i + 1]));
        }
        return results;
    }

    @Test
    void anAnswerWithoutAScoreIsAlwaysMovedOnFrom() {
        FollowUpInput pending = base(null).keyPoints(keyPoints("kp1", MISSING)).claims("used Kafka").build();

        assertThat(FollowUpPolicy.decide(pending)).isEqualTo(FollowUpDecision.moveOn(Reason.NO_SCORE));
    }

    @ParameterizedTest(name = "depth {0} of max {1}, {2} s left -> follow-up asked: {3}")
    @CsvSource({
        "0, 2, 600, true",
        "1, 2, 600, true",
        "2, 2, 600, false",
        "0, 1, 600, true",
        "1, 1, 600, false",
        "0, 0, 600, false",
        "0, 2, 181, true",
        "0, 2, 180, true",
        "0, 2, 179, false",
        "0, 2, 0, false"
    })
    void depthAndSectionTimeComeBeforeAnyOtherRule(int depth, int max, long secondsLeft, boolean asked) {
        FollowUpInput input = base(60.0).depth(depth, max).timeLeft(Duration.ofSeconds(secondsLeft))
                .keyPoints(keyPoints("kp1", MISSING)).build();

        assertThat(FollowUpPolicy.decide(input).asksFollowUp()).isEqualTo(asked);
    }

    @Test
    void justUnderThreeMinutesLeftMovesOn() {
        FollowUpInput almost = base(60.0).timeLeft(Duration.ofMinutes(3).minusNanos(1)).keyPoints(keyPoints("kp1", MISSING)).build();
        FollowUpInput exactly = base(60.0).timeLeft(Duration.ofMinutes(3)).keyPoints(keyPoints("kp1", MISSING)).build();

        assertThat(FollowUpPolicy.decide(almost).asksFollowUp()).isFalse();
        assertThat(FollowUpPolicy.decide(exactly).asksFollowUp()).isTrue();
    }

    @Test
    void timeAndDepthBlockEvenAClaimProbeAndADeepening() {
        FollowUpInput claim = base(60.0).depth(2, 2).claims("used Kafka").build();
        FollowUpInput deepen = base(95.0).timeLeft(Duration.ofSeconds(170)).deeper().build();

        assertThat(FollowUpPolicy.decide(claim)).isEqualTo(FollowUpDecision.moveOn(Reason.DEPTH_LIMIT));
        assertThat(FollowUpPolicy.decide(deepen)).isEqualTo(FollowUpDecision.moveOn(Reason.SECTION_TIME));
    }

    @ParameterizedTest(name = "score {0} with a missing key point -> {1}")
    @CsvSource({
        "0, MOVE_ON",
        "34, MOVE_ON",
        "34.99, MOVE_ON",
        "35, PROBE_GAP",
        "35.01, PROBE_GAP",
        "60, PROBE_GAP",
        "79.99, PROBE_GAP",
        "80, MOVE_ON",
        "80.01, MOVE_ON",
        "100, MOVE_ON"
    })
    void aGapIsProbedOnlyBetweenThirtyFiveAndEighty(double score, String expected) {
        FollowUpInput input = base(score).keyPoints(keyPoints("kp1", MISSING)).build();

        FollowUpDecision decision = FollowUpPolicy.decide(input);

        if (expected.equals("MOVE_ON")) {
            assertThat(decision).isEqualTo(FollowUpDecision.moveOn(score < 35 ? Reason.TOO_WEAK : Reason.NOTHING_TO_PROBE));
        } else {
            assertThat(decision).isEqualTo(FollowUpDecision.probeGap("kp1"));
        }
    }

    @Test
    void theGapProbedIsTheFirstMissingKeyPointInImportanceOrder() {
        FollowUpInput input = base(50.0)
                .keyPoints(keyPoints("kp1", COVERED, "kp2", PARTIAL, "kp3", MISSING, "kp4", MISSING)).build();

        assertThat(FollowUpPolicy.decide(input)).isEqualTo(FollowUpDecision.probeGap("kp3"));
    }

    @Test
    void aPartialKeyPointIsProbedOnlyWhenNoneIsMissing() {
        assertThat(FollowUpPolicy.decide(base(50.0).keyPoints(keyPoints("kp1", COVERED, "kp2", PARTIAL, "kp3", PARTIAL)).build()))
                .isEqualTo(FollowUpDecision.probeGap("kp2"));
        assertThat(FollowUpPolicy.decide(base(50.0).keyPoints(keyPoints("kp1", PARTIAL, "kp2", MISSING)).build()))
                .isEqualTo(FollowUpDecision.probeGap("kp2"));
    }

    @Test
    void whenEveryKeyPointIsCoveredOrThereAreNoneThereIsNoGapToProbe() {
        assertThat(FollowUpPolicy.decide(base(60.0).keyPoints(keyPoints("kp1", COVERED, "kp2", COVERED)).build()))
                .isEqualTo(FollowUpDecision.moveOn(Reason.NOTHING_TO_PROBE));
        assertThat(FollowUpPolicy.decide(base(60.0).build())).isEqualTo(FollowUpDecision.moveOn(Reason.NOTHING_TO_PROBE));
    }

    @Test
    void aGapIsProbedBeforeAClaim() {
        FollowUpInput input = base(60.0).keyPoints(keyPoints("kp1", MISSING)).claims("used Kafka").build();

        assertThat(FollowUpPolicy.decide(input)).isEqualTo(FollowUpDecision.probeGap("kp1"));
    }

    @ParameterizedTest(name = "score {0} -> claim probed: {1}")
    @CsvSource({"34.99, false", "35, true", "60, true", "79.99, true", "80, true", "84.99, true", "95, true"})
    void aNewClaimIsProbedWhenThereIsNoGapAndTheCandidateIsNotFailing(double score, boolean probed) {
        FollowUpInput input = base(score).keyPoints(keyPoints("kp1", COVERED)).claims("used Kafka", "ran a team").build();

        FollowUpDecision decision = FollowUpPolicy.decide(input);

        assertThat(decision.asksFollowUp()).isEqualTo(probed);
        if (probed) {
            assertThat(decision).isEqualTo(FollowUpDecision.probeClaim("used Kafka"));
        }
    }

    @Test
    void aClaimIsNotProbedWhenTheSectionHasAlreadyProbedOne() {
        FollowUpInput input = base(60.0).claims("used Kafka").claimProbeAllowed(false).build();

        assertThat(FollowUpPolicy.decide(input)).isEqualTo(FollowUpDecision.moveOn(Reason.NOTHING_TO_PROBE));
    }

    @Test
    void aClaimIsProbedAtTheTopOfTheScaleBeforeDeepening() {
        FollowUpInput input = base(95.0).claims("used Kafka").deeper().build();

        assertThat(FollowUpPolicy.decide(input)).isEqualTo(FollowUpDecision.probeClaim("used Kafka"));
    }

    @ParameterizedTest(name = "score {0}, {1} s left, deeper level available {2} -> deepen: {3}")
    @CsvSource({
        "85, 240, true, true",
        "100, 600, true, true",
        "84.99, 600, true, false",
        "80, 600, true, false",
        "85, 239, true, false",
        "85, 181, true, false",
        "85, 600, false, false"
    })
    void deepenNeedsEightyFivePointsFourMinutesAndAHarderLevel(double score, long secondsLeft, boolean deeper, boolean deepen) {
        Builder builder = base(score).timeLeft(Duration.ofSeconds(secondsLeft));
        if (deeper) {
            builder.deeper();
        }

        FollowUpDecision decision = FollowUpPolicy.decide(builder.build());

        assertThat(decision).isEqualTo(deepen ? FollowUpDecision.deepen() : FollowUpDecision.moveOn(Reason.NOTHING_TO_PROBE));
    }

    @Test
    void decisionsCarryTheirMode() {
        assertThat(FollowUpDecision.deepen().mode()).isEqualTo(FollowUpMode.DEEPEN);
        assertThat(FollowUpDecision.probeGap("kp1").mode()).isEqualTo(FollowUpMode.PROBE_GAP);
        assertThat(FollowUpDecision.probeGap("kp1").targetKeyPointId()).isEqualTo("kp1");
        assertThat(FollowUpDecision.probeClaim("c").mode()).isEqualTo(FollowUpMode.PROBE_CLAIM);
        assertThat(FollowUpDecision.probeClaim("c").targetClaim()).isEqualTo("c");
        assertThat(FollowUpDecision.moveOn(Reason.TOO_WEAK).mode()).isNull();
        assertThat(FollowUpDecision.moveOn(Reason.TOO_WEAK).asksFollowUp()).isFalse();
        assertThat(FollowUpDecision.probeGap("kp1").reason()).isEqualTo(Reason.KEY_POINT_GAP);
        assertThat(FollowUpDecision.probeClaim("c").reason()).isEqualTo(Reason.UNPROBED_CLAIM);
        assertThat(FollowUpDecision.deepen().reason()).isEqualTo(Reason.EXCELLENT);
        assertThat(FollowUpDecision.deepen().asksFollowUp()).isTrue();
    }

    @Test
    void decisionConstructorKeepsModeAndTargetConsistent() {
        assertThatThrownBy(() -> new FollowUpDecision(FollowUpMode.PROBE_GAP, null, null, Reason.KEY_POINT_GAP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpDecision(FollowUpMode.DEEPEN, "kp1", null, Reason.EXCELLENT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpDecision(FollowUpMode.PROBE_CLAIM, null, null, Reason.UNPROBED_CLAIM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpDecision(FollowUpMode.DEEPEN, null, "claim", Reason.EXCELLENT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpDecision(null, "kp1", null, Reason.TOO_WEAK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpDecision(null, null, "c", Reason.TOO_WEAK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpDecision(FollowUpMode.DEEPEN, null, null, Reason.TOO_WEAK))
                .as("a reason that does not fit the mode").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpDecision(null, null, null, Reason.EXCELLENT))
                .as("moving on for a reason that asks a follow-up").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpDecision(null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FollowUpDecision.probeGap(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FollowUpDecision.probeClaim(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRepeatedAnswerIsNeverFollowedUpWhateverItsScore() {
        assertThat(FollowUpPolicy.decide(base(60.0).keyPoints(keyPoints("kp1", MISSING)).repeated().build()))
                .isEqualTo(FollowUpDecision.moveOn(Reason.REPEATED_ANSWER));
        assertThat(FollowUpPolicy.decide(base(95.0).claims("used Kafka").deeper().repeated().build()))
                .isEqualTo(FollowUpDecision.moveOn(Reason.REPEATED_ANSWER));
    }

    @Test
    void inTheClosingWindowOnlyAGapIsStillProbed() {
        assertThat(FollowUpPolicy.decide(base(60.0).keyPoints(keyPoints("kp1", MISSING)).closing().build()))
                .isEqualTo(FollowUpDecision.probeGap("kp1"));
        assertThat(FollowUpPolicy.decide(base(60.0).claims("used Kafka").closing().build()))
                .isEqualTo(FollowUpDecision.moveOn(Reason.CLOSING));
        assertThat(FollowUpPolicy.decide(base(95.0).deeper().closing().build())).isEqualTo(FollowUpDecision.moveOn(Reason.CLOSING));
    }

    @Test
    void aFollowUpMayNotUseTheTimeReservedForSkillsNotAskedYet() {
        Builder gap = base(60.0).keyPoints(keyPoints("kp1", MISSING)).timeLeft(Duration.ofMinutes(10));

        assertThat(FollowUpPolicy.decide(gap.reserved(Duration.ofMinutes(8)).build())).as("2 spare minutes")
                .isEqualTo(FollowUpDecision.moveOn(Reason.BREADTH_RESERVE));
        assertThat(FollowUpPolicy.decide(gap.reserved(Duration.ofMinutes(7)).build())).as("3 spare minutes")
                .isEqualTo(FollowUpDecision.probeGap("kp1"));
        assertThat(FollowUpPolicy.decide(base(95.0).deeper().timeLeft(Duration.ofMinutes(10)).reserved(Duration.ofMinutes(7)).build()))
                .as("deepening needs 4 spare minutes").isEqualTo(FollowUpDecision.moveOn(Reason.NOTHING_TO_PROBE));
        assertThat(FollowUpPolicy.decide(base(95.0).deeper().timeLeft(Duration.ofMinutes(10)).reserved(Duration.ofMinutes(6)).build()))
                .isEqualTo(FollowUpDecision.deepen());
        assertThat(base(60.0).timeLeft(Duration.ofMinutes(2)).reserved(Duration.ofMinutes(4)).build().spareTime())
                .as("never negative").isEqualTo(Duration.ZERO);
    }

    @Test
    void everyReasonHasADescription() {
        for (Reason reason : Reason.values()) {
            assertThat(reason.description()).isNotBlank();
        }
    }

    @Test
    void inputIsValidated() {
        assertThatThrownBy(() -> base(100.01).build()).hasMessageContaining("overallScore");
        assertThatThrownBy(() -> base(-0.01).build()).hasMessageContaining("overallScore");
        assertThatThrownBy(() -> base(50.0).depth(3, 2).build()).hasMessageContaining("followUpDepth");
        assertThatThrownBy(() -> base(50.0).depth(0, 3).build()).hasMessageContaining("maxFollowUpDepth");
        assertThatThrownBy(() -> base(50.0).timeLeft(Duration.ofSeconds(-1)).build()).hasMessageContaining("negative");
        assertThatThrownBy(() -> base(50.0).claims(" ").build()).hasMessageContaining("newClaims");
        assertThatThrownBy(() -> base(50.0).reserved(Duration.ofSeconds(-1)).build()).hasMessageContaining("reservedForBreadth");
        assertThatThrownBy(() -> base(50.0).reserved(null).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyPointResult(" ", MISSING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyPointResult("kp1", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
