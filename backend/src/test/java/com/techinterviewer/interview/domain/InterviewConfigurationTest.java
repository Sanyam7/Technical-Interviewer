package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class InterviewConfigurationTest {

    private static final UUID OWNER = UuidV7.generate();
    private static final Instant NOW = Fixtures.NOW;

    private static InterviewConfiguration create(int minutes, InterviewType type, String language, List<Long> skills) {
        return InterviewConfiguration.create(
                OWNER, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, minutes, type, language, true, skills, NOW);
    }

    @Test
    void aConfigurationHoldsExactlyWhatItWasCreatedWith() {
        InterviewConfiguration configuration = InterviewConfiguration.create(
                OWNER, TargetRole.FULL_STACK, ExperienceLevel.STAFF, InterviewDifficulty.HARD, 90, InterviewType.CODING,
                "python", true, List.of(7L, 3L), NOW);

        assertThat(configuration.getOwnerUserId()).isEqualTo(OWNER);
        assertThat(configuration.getRole()).isEqualTo(TargetRole.FULL_STACK);
        assertThat(configuration.getExperienceLevel()).isEqualTo(ExperienceLevel.STAFF);
        assertThat(configuration.getDifficulty()).isEqualTo(InterviewDifficulty.HARD);
        assertThat(configuration.getDurationMinutes()).isEqualTo(90);
        assertThat(configuration.getInterviewType()).isEqualTo(InterviewType.CODING);
        assertThat(configuration.getCodingLanguage()).isEqualTo("python");
        assertThat(configuration.isUseResume()).isTrue();
        assertThat(configuration.getSkillIds()).containsExactly(7L, 3L);
        assertThat(configuration.getCreatedAt()).isEqualTo(NOW);
        assertThat(UuidV7.isV7(configuration.getId())).isTrue();
    }

    @Test
    void useResumeFalseIsKept() {
        InterviewConfiguration configuration = Fixtures.technicalConfiguration(OWNER, Set.of());

        assertThat(configuration.isUseResume()).isFalse();
    }

    @ParameterizedTest(name = "{0} minutes is allowed")
    @ValueSource(ints = {15, 30, 45, 60, 90})
    void theFiveLengthsOfTheSchemaAreAllowed(int minutes) {
        assertThat(create(minutes, InterviewType.TECHNICAL, null, List.of()).getDurationMinutes()).isEqualTo(minutes);
    }

    @ParameterizedTest(name = "{0} minutes is refused")
    @ValueSource(ints = {-30, 0, 1, 14, 16, 29, 31, 44, 46, 61, 75, 89, 91, 120})
    void anyOtherLengthIsRefused(int minutes) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> create(minutes, InterviewType.TECHNICAL, null, List.of()))
                .withMessageContaining("durationMinutes")
                .withMessageContaining("[15, 30, 45, 60, 90]");
    }

    @ParameterizedTest(name = "{0}: language required = {1}")
    @CsvSource({"TECHNICAL, false", "SYSTEM_DESIGN, false", "CODING, true", "FULL_LOOP, true"})
    void aCodingLanguageIsRequiredExactlyForTheTypesWithACodingSection(InterviewType type, boolean required) {
        assertThat(InterviewConfiguration.requiresCodingLanguage(type)).isEqualTo(required);
        if (required) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> create(60, type, null, List.of()))
                    .withMessageContaining("codingLanguage is required for a " + type);
            assertThatIllegalArgumentException().isThrownBy(() -> create(60, type, "  ", List.of()));
        } else {
            assertThat(create(60, type, null, List.of()).getCodingLanguage()).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(InterviewType.class)
    void everyTypeAcceptsALanguageWhenOneIsGiven(InterviewType type) {
        assertThat(create(60, type, "java", List.of()).getCodingLanguage()).isEqualTo("java");
    }

    @Test
    void theCodingLanguageIsBoundedByTheColumn() {
        assertThat(create(60, InterviewType.CODING, "x".repeat(20), List.of()).getCodingLanguage()).hasSize(20);
        assertThatIllegalArgumentException().isThrownBy(() -> create(60, InterviewType.CODING, "x".repeat(21), List.of()));
    }

    @Test
    void requiredValuesAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(
                null, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30, InterviewType.TECHNICAL, null, false, List.of(), NOW)).withMessageContaining("ownerUserId");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(
                OWNER, null, ExperienceLevel.MID, InterviewDifficulty.EASY, 30, InterviewType.TECHNICAL, null, false, List.of(), NOW)).withMessageContaining("role");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(
                OWNER, TargetRole.BACKEND, null, InterviewDifficulty.EASY, 30, InterviewType.TECHNICAL, null, false, List.of(), NOW)).withMessageContaining("experienceLevel");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(
                OWNER, TargetRole.BACKEND, ExperienceLevel.MID, null, 30, InterviewType.TECHNICAL, null, false, List.of(), NOW)).withMessageContaining("difficulty");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(
                OWNER, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30, null, null, false, List.of(), NOW)).withMessageContaining("interviewType");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(
                OWNER, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30, InterviewType.TECHNICAL, null, false, null, NOW)).withMessageContaining("skillIds");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(
                OWNER, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30, InterviewType.TECHNICAL, null, false, List.of(), null)).withMessageContaining("now");
    }

    @Test
    void skillIdsMustBePositiveAndNotNull() {
        List<Long> withZero = List.of(1L, 0L);
        List<Long> withNegative = List.of(-4L);
        List<Long> withNull = new ArrayList<>(Arrays.asList(1L, null));

        assertThatIllegalArgumentException().isThrownBy(() -> create(30, InterviewType.TECHNICAL, null, withZero)).withMessageContaining("positive");
        assertThatIllegalArgumentException().isThrownBy(() -> create(30, InterviewType.TECHNICAL, null, withNegative)).withMessageContaining("positive");
        assertThatIllegalArgumentException().isThrownBy(() -> create(30, InterviewType.TECHNICAL, null, withNull)).withMessageContaining("positive");
    }

    @Test
    void duplicateSkillIdsCollapseAndTheCallersListCannotChangeTheConfiguration() {
        List<Long> given = new ArrayList<>(List.of(5L, 5L, 9L));
        InterviewConfiguration configuration = create(30, InterviewType.TECHNICAL, null, given);

        given.add(11L);

        assertThat(configuration.getSkillIds()).containsExactly(5L, 9L);
    }

    @Test
    void theSkillSetCannotBeModifiedThroughTheGetter() {
        InterviewConfiguration configuration = create(30, InterviewType.TECHNICAL, null, List.of(5L));

        assertThat(List.of(configuration.getSkillIds())).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> configuration.getSkillIds().add(6L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aConfigurationStoresItsStrategyAndTheStageMixItUses() {
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T10:00:00Z");

        InterviewConfiguration standard = InterviewConfiguration.create(owner, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM,
                30, InterviewType.TECHNICAL, null, false, List.of(1L), now);
        InterviewConfiguration custom = InterviewConfiguration.create(owner, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM,
                30, InterviewType.TECHNICAL, null, false, InterviewStrategy.CUSTOM, new StageMix(0, 70, 30), List.of(1L), now);

        assertThat(standard.getStrategy()).isEqualTo(InterviewStrategy.FUNDAMENTALS_FIRST);
        assertThat(standard.getStageMix()).isEqualTo(new StageMix(60, 25, 15));
        assertThat(custom.getStageMix()).isEqualTo(new StageMix(0, 70, 30));
    }

    @Test
    void theStrategyRulesHoldOnTheEntityToo() {
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T10:00:00Z");

        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(owner, TargetRole.BACKEND, ExperienceLevel.MID,
                InterviewDifficulty.MEDIUM, 30, InterviewType.TECHNICAL, null, false, InterviewStrategy.CUSTOM, null, List.of(1L), now));
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(owner, TargetRole.BACKEND, ExperienceLevel.MID,
                InterviewDifficulty.MEDIUM, 30, InterviewType.TECHNICAL, null, false, InterviewStrategy.BALANCED, new StageMix(0, 70, 30), List.of(1L), now));
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewConfiguration.create(owner, TargetRole.BACKEND, ExperienceLevel.MID,
                InterviewDifficulty.MEDIUM, 30, InterviewType.TECHNICAL, null, false, InterviewStrategy.RESUME_FOCUSED, null, List.of(1L), now));
        assertThatIllegalArgumentException().isThrownBy(() -> new StageMix(50, 50, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new StageMix(-10, 60, 50));
    }
}
