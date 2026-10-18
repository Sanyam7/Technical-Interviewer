package com.techinterviewer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CandidateProfileTest {

    private static final Instant T0 = TestClock.START;
    private static final Instant T1 = T0.plus(Duration.ofMinutes(5));

    private final UUID user = UUID.randomUUID();

    @Test
    void aNewProfileIsEmptyAndRemembersWhenItWasCreated() {
        CandidateProfile profile = new CandidateProfile(user, T0);

        assertThat(profile.getUserId()).isEqualTo(user);
        assertThat(profile.getHeadline()).isNull();
        assertThat(profile.getYearsExperience()).isNull();
        assertThat(profile.getJobTitle()).isNull();
        assertThat(profile.getSkillRatings()).isEmpty();
        assertThat(profile.getCreatedAt()).isEqualTo(T0);
        assertThat(profile.getUpdatedAt()).isEqualTo(T0);
        assertThat(profile.getId()).isNotNull();
    }

    @Test
    void replaceSetsEveryFieldAndMovesOnlyTheUpdateTime() {
        CandidateProfile profile = new CandidateProfile(user, T0);

        profile.replace("Backend engineer", 7, "Staff Engineer", Map.of(1L, 4, 2L, 5), T1);

        assertThat(profile.getHeadline()).isEqualTo("Backend engineer");
        assertThat(profile.getYearsExperience()).isEqualTo(7);
        assertThat(profile.getJobTitle()).isEqualTo("Staff Engineer");
        assertThat(profile.getSkillRatings()).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 4, 2L, 5));
        assertThat(profile.getCreatedAt()).isEqualTo(T0);
        assertThat(profile.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void replaceDropsSkillsThatAreNotListedAndUpdatesRatingsOfTheOnesThatAre() {
        CandidateProfile profile = new CandidateProfile(user, T0);
        profile.replace(null, null, null, Map.of(1L, 2, 2L, 3, 3L, 4), T0);

        profile.replace(null, null, null, Map.of(2L, 5, 4L, 1), T1);

        assertThat(profile.getSkillRatings()).containsExactlyInAnyOrderEntriesOf(Map.of(2L, 5, 4L, 1));
    }

    @Test
    void replaceWithNullsClearsTheFieldsAndWithNoSkillsClearsTheSkills() {
        CandidateProfile profile = new CandidateProfile(user, T0);
        profile.replace("h", 3, "t", Map.of(1L, 3), T0);

        profile.replace(null, null, null, Map.of(), T1);

        assertThat(profile.getHeadline()).isNull();
        assertThat(profile.getYearsExperience()).isNull();
        assertThat(profile.getJobTitle()).isNull();
        assertThat(profile.getSkillRatings()).isEmpty();
    }

    @Test
    void theExposedSkillRatingsCannotBeUsedToChangeTheProfile() {
        CandidateProfile profile = new CandidateProfile(user, T0);
        profile.replace(null, null, null, Map.of(1L, 3), T0);

        assertThatThrownBy(() -> profile.getSkillRatings().put(2L, 5)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(profile.getSkillRatings()).containsOnlyKeys(1L);
    }

    @Test
    void laterChangesToTheCallersMapDoNotLeakIntoTheProfile() {
        CandidateProfile profile = new CandidateProfile(user, T0);
        Map<Long, Integer> ratings = new LinkedHashMap<>(Map.of(1L, 3));
        profile.replace(null, null, null, ratings, T0);

        ratings.put(2L, 5);

        assertThat(profile.getSkillRatings()).containsOnlyKeys(1L);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 61, 1000})
    void yearsOutsideZeroToSixtyAreRejected(int years) {
        CandidateProfile profile = new CandidateProfile(user, T0);

        assertThatThrownBy(() -> profile.replace(null, years, null, Map.of(), T1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 60})
    void yearsAtTheBoundsAreAccepted(int years) {
        CandidateProfile profile = new CandidateProfile(user, T0);

        profile.replace(null, years, null, Map.of(), T1);

        assertThat(profile.getYearsExperience()).isEqualTo(years);
    }

    @Test
    void textLongerThanTheColumnsOrBlankIsRejectedAndMaximumLengthAccepted() {
        CandidateProfile profile = new CandidateProfile(user, T0);

        assertThatThrownBy(() -> profile.replace("h".repeat(121), null, null, Map.of(), T1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.replace(null, null, "t".repeat(61), Map.of(), T1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.replace(" ", null, null, Map.of(), T1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.replace(null, null, "", Map.of(), T1)).isInstanceOf(IllegalArgumentException.class);

        profile.replace("h".repeat(120), null, "t".repeat(60), Map.of(), T1);
        assertThat(profile.getHeadline()).hasSize(120);
        assertThat(profile.getJobTitle()).hasSize(60);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 6, 100})
    void ratingsOutsideOneToFiveAreRejected(int rating) {
        CandidateProfile profile = new CandidateProfile(user, T0);

        assertThatThrownBy(() -> profile.replace(null, null, null, Map.of(1L, rating), T1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atMostTwentySkillsAreAccepted() {
        CandidateProfile profile = new CandidateProfile(user, T0);
        Map<Long, Integer> twenty = new LinkedHashMap<>();
        for (long id = 1; id <= 20; id++) {
            twenty.put(id, 3);
        }
        profile.replace(null, null, null, twenty, T1);
        assertThat(profile.getSkillRatings()).hasSize(20);

        twenty.put(21L, 3);
        assertThatThrownBy(() -> profile.replace(null, null, null, twenty, T1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRejectedReplaceChangesNothing() {
        CandidateProfile profile = new CandidateProfile(user, T0);
        profile.replace("keep", 5, "keep", Map.of(1L, 5), T0);

        assertThatThrownBy(() -> profile.replace("new", 61, "new", Map.of(2L, 1), T1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.replace("new", 6, "new", Map.of(2L, 9), T1)).isInstanceOf(IllegalArgumentException.class);

        assertThat(profile.getHeadline()).isEqualTo("keep");
        assertThat(profile.getSkillRatings()).containsOnlyKeys(1L);
        assertThat(profile.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void constructorRejectsMissingArguments() {
        assertThatThrownBy(() -> new CandidateProfile(null, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidateProfile(user, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
