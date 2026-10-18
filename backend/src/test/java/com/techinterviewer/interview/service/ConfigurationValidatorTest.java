package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.common.error.FieldViolation;
import com.techinterviewer.interview.api.dto.ConfigurationRequest;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.techinterviewer.interview.api.dto.StageMixView;
import com.techinterviewer.interview.domain.InterviewStrategy;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigurationValidatorTest {

    private static ConfigurationRequest request(InterviewType type, int minutes, List<String> skills, @Nullable String language, @Nullable Boolean useResume) {
        return new ConfigurationRequest(
                TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, minutes, type, skills, language, useResume);
    }

    private static ConfigurationRequest valid() {
        return request(InterviewType.TECHNICAL, 45, List.of("java", "spring-boot"), null, null);
    }

    private static List<String> fields(List<FieldViolation> violations) {
        return violations.stream().map(FieldViolation::field).toList();
    }

    private static ConfigurationRequest strategy(@Nullable InterviewStrategy strategy, @Nullable StageMixView mix, @Nullable Boolean useResume) {
        return new ConfigurationRequest(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 45, InterviewType.TECHNICAL,
                List.of("java"), null, useResume, strategy, mix);
    }

    // ---- the strategy (AI_ARCHITECTURE.md section 2.15) ----------------------------------------------------------------

    @Test
    void aPresetStrategyNeedsNoMixAndTheDefaultIsFundamentalsFirst() {
        assertThat(ConfigurationValidator.rules(strategy(null, null, null), "")).isEmpty();
        assertThat(ConfigurationValidator.rules(strategy(InterviewStrategy.BALANCED, null, null), "")).isEmpty();
    }

    @Test
    void aCustomStrategyNeedsAMixThatAddsUpTo100() {
        assertThat(ConfigurationValidator.rules(strategy(InterviewStrategy.CUSTOM, new StageMixView(50, 30, 20), null), "")).isEmpty();
        assertThat(fields(ConfigurationValidator.rules(strategy(InterviewStrategy.CUSTOM, null, null), "configuration.")))
                .containsExactly("configuration.stageMix");
        List<FieldViolation> wrongTotal = ConfigurationValidator.rules(strategy(InterviewStrategy.CUSTOM, new StageMixView(50, 30, 30), null), "");
        assertThat(wrongTotal).extracting(FieldViolation::message).containsExactly("The shares must add up to 100 but add up to 110.");
    }

    @Test
    void onlyACustomStrategyTakesAMix() {
        assertThat(fields(ConfigurationValidator.rules(strategy(InterviewStrategy.FUNDAMENTALS_FIRST, new StageMixView(50, 30, 20), null), "")))
                .containsExactly("stageMix");
    }

    @Test
    void aResumeFocusedInterviewMustBePersonalisedFromTheResume() {
        assertThat(fields(ConfigurationValidator.rules(strategy(InterviewStrategy.RESUME_FOCUSED, null, false), ""))).containsExactly("strategy");
        assertThat(ConfigurationValidator.rules(strategy(InterviewStrategy.RESUME_FOCUSED, null, true), "")).isEmpty();
    }

    // ---- the pure rules -----------------------------------------------------------------------------------------------

    @Test
    void aValidConfigurationBreaksNoRule() {
        assertThat(ConfigurationValidator.rules(valid(), "")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 30, 45, 60, 90})
    void everyOfferedDurationIsAccepted(int minutes) {
        assertThat(ConfigurationValidator.rules(request(InterviewType.TECHNICAL, minutes, List.of("java"), null, null), "")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-45, 0, 14, 16, 29, 44, 46, 75, 91, 120})
    void aDurationThatIsNotOfferedIsRejectedOnItsField(int minutes) {
        List<FieldViolation> violations = ConfigurationValidator.rules(
                request(InterviewType.TECHNICAL, minutes, List.of("java"), null, null), "");

        assertThat(violations).containsExactly(new FieldViolation("durationMinutes", "Must be one of [15, 30, 45, 60, 90]."));
    }

    @Test
    void aRepeatedSkillIsRejectedOnEveryRepeatButTheFirstOccurrence() {
        List<FieldViolation> violations = ConfigurationValidator.rules(
                request(InterviewType.TECHNICAL, 45, List.of("java", "spring-boot", "java", "spring-boot", "java"), null, null), "");

        assertThat(violations).containsExactly(
                new FieldViolation("skills[2]", "Skill 'java' is listed more than once."),
                new FieldViolation("skills[3]", "Skill 'spring-boot' is listed more than once."),
                new FieldViolation("skills[4]", "Skill 'java' is listed more than once."));
    }

    @ParameterizedTest
    @EnumSource(value = InterviewType.class, names = {"CODING", "FULL_LOOP"})
    void aCodingOrFullLoopInterviewNeedsACodingLanguage(InterviewType type) {
        List<FieldViolation> violations = ConfigurationValidator.rules(request(type, 45, List.of("java"), null, null), "");

        assertThat(violations).containsExactly(new FieldViolation("codingLanguage", "Required for a " + type + " interview."));
    }

    @ParameterizedTest
    @EnumSource(value = InterviewType.class, names = {"TECHNICAL", "SYSTEM_DESIGN"})
    void aTechnicalOrDesignInterviewMayNotCarryACodingLanguage(InterviewType type) {
        List<FieldViolation> violations = ConfigurationValidator.rules(request(type, 45, List.of("java"), "java", null), "");

        assertThat(violations).containsExactly(new FieldViolation("codingLanguage", "Not allowed for a " + type + " interview."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "javascript", "typescript", "python", "go"})
    void everyOfferedCodingLanguageIsAcceptedForCoding(String language) {
        assertThat(ConfigurationValidator.rules(request(InterviewType.CODING, 45, List.of("java"), language, null), "")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Java", "JAVA", "rust", "", " java", "c++"})
    void aCodingLanguageThatIsNotOfferedIsRejectedExactly(String language) {
        List<FieldViolation> violations = ConfigurationValidator.rules(request(InterviewType.FULL_LOOP, 45, List.of("java"), language, null), "");

        assertThat(violations).containsExactly(
                new FieldViolation("codingLanguage", "Must be one of [java, javascript, typescript, python, go]."));
    }

    @Test
    void anEmptyCodingLanguageIsNotTreatedAsAbsentForTechnicalInterviews() {
        assertThat(ConfigurationValidator.rules(request(InterviewType.TECHNICAL, 45, List.of("java"), "", null), ""))
                .containsExactly(new FieldViolation("codingLanguage", "Not allowed for a TECHNICAL interview."));
    }

    @Test
    void resumePersonalisationIsAcceptedWhetherRequestedOrNot() {
        // Whether a résumé exists is checked when an interview is created: a configuration may be saved before the upload.
        assertThat(ConfigurationValidator.rules(request(InterviewType.TECHNICAL, 45, List.of("java"), null, true), "")).isEmpty();
        assertThat(ConfigurationValidator.rules(request(InterviewType.TECHNICAL, 45, List.of("java"), null, false), "")).isEmpty();
        assertThat(ConfigurationValidator.rules(request(InterviewType.TECHNICAL, 45, List.of("java"), null, null), "")).isEmpty();
    }

    @Test
    void theFieldPrefixIsAppliedToEveryReportedField() {
        ConfigurationRequest broken = request(InterviewType.CODING, 20, List.of("java", "java"), null, true);

        assertThat(fields(ConfigurationValidator.rules(broken, "configuration.")))
                .containsExactlyInAnyOrder("configuration.durationMinutes", "configuration.skills[1]",
                        "configuration.codingLanguage");
    }

    // ---- with the catalog -------------------------------------------------------------------------------------------

    private static CatalogService catalogKnowing(String... slugs) {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.requireActiveSkills(anyList(), any())).thenAnswer(call -> {
            List<String> wanted = call.getArgument(0);
            IntFunction<String> fieldPath = call.getArgument(1);
            List<String> known = List.of(slugs);
            List<FieldViolation> unknown = new ArrayList<>();
            List<SkillRef> found = new ArrayList<>();
            for (int i = 0; i < wanted.size(); i++) {
                int index = known.indexOf(wanted.get(i));
                if (index < 0) {
                    unknown.add(new FieldViolation(fieldPath.apply(i), "Unknown or inactive skill '" + wanted.get(i) + "'."));
                } else {
                    found.add(new SkillRef(index + 1, wanted.get(i), wanted.get(i).toUpperCase()));
                }
            }
            if (!unknown.isEmpty()) {
                throw new FieldValidationException(unknown);
            }
            return found;
        });
        return catalog;
    }

    @Test
    void aValidRequestIsResolvedToItsSkillsInTheOrderGiven() {
        ValidatedConfiguration validated = new ConfigurationValidator(catalogKnowing("java", "spring-boot"))
                .validate(request(InterviewType.CODING, 30, List.of("spring-boot", "java"), "go", false), "");

        assertThat(validated.skills()).extracting(SkillRef::slug).containsExactly("spring-boot", "java");
        assertThat(validated.codingLanguage()).isEqualTo("go");
        assertThat(validated.durationMinutes()).isEqualTo(30);
        assertThat(validated.interviewType()).isEqualTo(InterviewType.CODING);
    }

    @Test
    void unknownSkillsAndBrokenRulesAreReportedTogetherSortedByField() {
        ConfigurationRequest broken = request(InterviewType.CODING, 20, List.of("java", "cobol", "java"), null, null);

        assertThatThrownBy(() -> new ConfigurationValidator(catalogKnowing("java")).validate(broken, "configuration."))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.violations()).containsExactly(
                        new FieldViolation("configuration.codingLanguage", "Required for a CODING interview."),
                        new FieldViolation("configuration.durationMinutes", "Must be one of [15, 30, 45, 60, 90]."),
                        new FieldViolation("configuration.skills[1]", "Unknown or inactive skill 'cobol'."),
                        new FieldViolation("configuration.skills[2]", "Skill 'java' is listed more than once.")));
    }

    @Test
    void onlyUnknownSkillsAreReportedWhenNothingElseIsWrong() {
        assertThatThrownBy(() -> new ConfigurationValidator(catalogKnowing("java")).validate(
                request(InterviewType.TECHNICAL, 45, List.of("cobol"), null, null), ""))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.violations())
                        .containsExactly(new FieldViolation("skills[0]", "Unknown or inactive skill 'cobol'.")));
    }

    @Test
    void theValidatedConfigurationBuildsAnEntityWithResumeOffAndTheResolvedSkillIds() {
        ValidatedConfiguration validated = new ConfigurationValidator(catalogKnowing("java", "spring-boot"))
                .validate(request(InterviewType.TECHNICAL, 45, List.of("spring-boot", "java"), null, null), "");
        UUID owner = UUID.randomUUID();

        InterviewConfiguration entity = validated.toEntity(owner, Instant.parse("2026-10-05T12:00:00Z"));

        assertThat(entity.getOwnerUserId()).isEqualTo(owner);
        assertThat(entity.isUseResume()).isFalse();
        assertThat(entity.getSkillIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(entity.getDurationMinutes()).isEqualTo(45);
        assertThat(entity.getCodingLanguage()).isNull();
    }
}
