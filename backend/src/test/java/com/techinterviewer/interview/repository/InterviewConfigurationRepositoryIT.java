package com.techinterviewer.interview.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import com.techinterviewer.persistence.RecordingStatementInspector;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

class InterviewConfigurationRepositoryIT extends PersistenceTestBase {

    private InterviewConfiguration saveConfiguration(UUID owner, Instant createdAt) {
        InterviewConfiguration configuration = InterviewConfiguration.create(
                owner, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 45, InterviewType.TECHNICAL,
                null, false, Set.of(), createdAt);
        inTransaction(() -> configurations.saveAndFlush(configuration));
        return configuration;
    }

    @Test
    void everyMappedFieldSurvivesAWriteAndAReadInAFreshPersistenceContext() {
        UUID owner = newUser();
        InterviewConfiguration original = Fixtures.configuration(owner, Set.copyOf(skillIds(3)));
        inTransaction(() -> configurations.saveAndFlush(original));

        InterviewConfiguration loaded = inTransaction(() -> configurations.findById(original.getId()).orElseThrow());

        assertThat(loaded).isNotSameAs(original);
        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getOwnerUserId()).isEqualTo(owner);
        assertThat(loaded.getRole()).isEqualTo(TargetRole.BACKEND);
        assertThat(loaded.getExperienceLevel()).isEqualTo(ExperienceLevel.SENIOR);
        assertThat(loaded.getDifficulty()).isEqualTo(InterviewDifficulty.HARD);
        assertThat(loaded.getDurationMinutes()).isEqualTo(60);
        assertThat(loaded.getInterviewType()).isEqualTo(InterviewType.FULL_LOOP);
        assertThat(loaded.getCodingLanguage()).isEqualTo("java");
        assertThat(loaded.isUseResume()).isTrue();
        assertThat(loaded.getCreatedAt()).isEqualTo(Fixtures.NOW);
        assertThat(loaded.getSkillIds()).containsExactlyInAnyOrderElementsOf(skillIds(3));
        assertThat(jdbc.queryForObject("select role from interview_configurations where id = ?", String.class, original.getId()))
                .isEqualTo("BACKEND");
        assertThat(jdbc.queryForObject("select experience_level from interview_configurations where id = ?", String.class, original.getId()))
                .isEqualTo("SENIOR");
    }

    @Test
    void aTechnicalConfigurationWithoutCodingLanguageRoundTripsWithNullLanguage() {
        UUID owner = newUser();
        InterviewConfiguration original = Fixtures.technicalConfiguration(owner, Set.of());
        inTransaction(() -> configurations.saveAndFlush(original));

        InterviewConfiguration loaded = inTransaction(() -> configurations.findById(original.getId()).orElseThrow());

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getCodingLanguage()).isNull();
        assertThat(loaded.isUseResume()).isFalse();
        assertThat(loaded.getSkillIds()).isEmpty();
    }

    @Test
    void aConfigurationIsFoundOnlyForItsOwner() {
        UUID owner = newUser();
        UUID stranger = newUser();
        InterviewConfiguration configuration = saveConfiguration(owner, Fixtures.NOW);

        assertThat(configurations.findByIdAndOwnerUserId(configuration.getId(), owner)).isPresent();
        assertThat(configurations.findByIdAndOwnerUserId(configuration.getId(), stranger)).isEmpty();
        assertThat(configurations.findByIdAndOwnerUserId(UuidV7.generate(), owner)).isEmpty();
    }

    @Test
    void theListOfAnOwnerHoldsOnlyTheirConfigurationsNewestFirstAndIsPaged() {
        UUID owner = newUser();
        UUID stranger = newUser();
        InterviewConfiguration oldest = saveConfiguration(owner, Fixtures.NOW);
        InterviewConfiguration middle = saveConfiguration(owner, Fixtures.NOW.plusSeconds(10));
        InterviewConfiguration newest = saveConfiguration(owner, Fixtures.NOW.plusSeconds(20));
        saveConfiguration(stranger, Fixtures.NOW.plusSeconds(30));

        Page<InterviewConfiguration> first = configurations.findPageByOwnerUserId(owner, PageRequest.of(0, 2));
        Page<InterviewConfiguration> second = configurations.findPageByOwnerUserId(owner, PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(InterviewConfiguration::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(second.getContent()).extracting(InterviewConfiguration::getId).containsExactly(oldest.getId());
        assertThat(configurations.findPageByOwnerUserId(UuidV7.generate(), PageRequest.of(0, 5)).getContent()).isEmpty();
    }

    @Test
    void theListLoadsTheSkillsOfEveryConfigurationWithoutAQueryPerConfiguration() {
        UUID owner = newUser();
        List<Long> skills = skillIds(2);
        for (int i = 0; i < 5; i++) {
            inTransaction(() -> configurations.saveAndFlush(Fixtures.configuration(owner, Set.copyOf(skills))));
        }
        RecordingStatementInspector.clear();

        List<InterviewConfiguration> page = inTransaction(
                () -> configurations.findPageByOwnerUserId(owner, PageRequest.of(0, 10)).getContent().stream()
                        .peek(configuration -> configuration.getSkillIds().size()).toList());

        assertThat(page).hasSize(5).allSatisfy(configuration -> assertThat(configuration.getSkillIds()).containsExactlyInAnyOrderElementsOf(skills));
        long skillSelects = RecordingStatementInspector.statements().stream()
                .filter(sql -> sql.contains("interview_configuration_skills")).count();
        assertThat(skillSelects).as("skills are fetched in a batch, not once per configuration").isEqualTo(1);
    }

    @Test
    void anOwnerDeletesTheirConfigurationAndItsSkillRowsGoWithIt() {
        UUID owner = newUser();
        InterviewConfiguration configuration = Fixtures.configuration(owner, Set.copyOf(skillIds(2)));
        inTransaction(() -> configurations.saveAndFlush(configuration));
        assertThat(count("interview_configuration_skills", "configuration_id", configuration.getId())).isEqualTo(2);

        long deleted = inTransaction(() -> configurations.deleteByIdAndOwnerUserId(configuration.getId(), owner));

        assertThat(deleted).isEqualTo(1);
        assertThat(count("interview_configurations", "id", configuration.getId())).isZero();
        assertThat(count("interview_configuration_skills", "configuration_id", configuration.getId())).isZero();
    }

    @Test
    void aStrangerCannotDeleteSomeoneElsesConfiguration() {
        UUID owner = newUser();
        InterviewConfiguration configuration = saveConfiguration(owner, Fixtures.NOW);

        long deleted = inTransaction(() -> configurations.deleteByIdAndOwnerUserId(configuration.getId(), newUser()));

        assertThat(deleted).isZero();
        assertThat(count("interview_configurations", "id", configuration.getId())).isOne();
    }

    @Test
    void aConfigurationUsedByAnInterviewCannotBeDeleted() {
        Scenario scenario = newScenario();

        assertThatThrownBy(() -> inTransaction(() -> configurations.deleteByIdAndOwnerUserId(
                scenario.configuration().getId(), scenario.userId())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("interview_configurations", "id", scenario.configuration().getId())).isOne();
        assertThat(count("interview_configuration_skills", "configuration_id", scenario.configuration().getId())).isEqualTo(2);
    }

    @Test
    void aConfigurationCannotReferToASkillThatDoesNotExist() {
        UUID owner = newUser();
        InterviewConfiguration configuration = Fixtures.configuration(owner, Set.of(Long.MAX_VALUE));

        assertThatThrownBy(() -> inTransaction(() -> configurations.saveAndFlush(configuration)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("interview_configurations", "id", configuration.getId())).isZero();
    }

    @Test
    void theConfigurationsOfAUserAreDeletedWithTheUser() {
        UUID owner = newUser();
        InterviewConfiguration configuration = Fixtures.configuration(owner, Set.copyOf(skillIds(1)));
        inTransaction(() -> configurations.saveAndFlush(configuration));

        jdbc.update("delete from users where id = ?", owner);

        assertThat(count("interview_configurations", "id", configuration.getId())).isZero();
        assertThat(count("interview_configuration_skills", "configuration_id", configuration.getId())).isZero();
    }
}
