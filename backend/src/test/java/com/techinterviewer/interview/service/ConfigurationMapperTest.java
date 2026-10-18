package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.interview.api.dto.ConfigurationResponse;
import com.techinterviewer.interview.api.dto.SkillSummary;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ConfigurationMapperTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final Map<Long, SkillRef> SKILLS = Map.of(
            1L, new SkillRef(1, "java", "Java"),
            2L, new SkillRef(2, "spring-boot", "Spring Boot"),
            3L, new SkillRef(3, "postgresql", "PostgreSQL"),
            4L, new SkillRef(4, "alpha", "Java"),
            5L, new SkillRef(5, "zulu", "Aardvark"));

    private static InterviewConfiguration configuration(List<Long> skillIds) {
        return InterviewConfiguration.create(
                UUID.randomUUID(), TargetRole.FULL_STACK, ExperienceLevel.SENIOR, InterviewDifficulty.HARD, 90,
                InterviewType.FULL_LOOP, "go", false, skillIds, T0);
    }

    @Test
    void everyFieldOfTheConfigurationIsMapped() {
        InterviewConfiguration configuration = configuration(List.of(1L));

        ConfigurationResponse response = ConfigurationMapper.toResponse(configuration, SKILLS);

        assertThat(response).isEqualTo(new ConfigurationResponse(
                configuration.getId(), TargetRole.FULL_STACK, ExperienceLevel.SENIOR, InterviewDifficulty.HARD, 90,
                InterviewType.FULL_LOOP, List.of(new SkillSummary("java", "Java")), "go", false,
                com.techinterviewer.interview.domain.InterviewStrategy.FUNDAMENTALS_FIRST,
                new com.techinterviewer.interview.api.dto.StageMixView(60, 25, 15), T0));
    }

    @Test
    void skillsAreListedByNameThenSlugWhateverOrderTheyWereStoredIn() {
        InterviewConfiguration configuration = configuration(List.of(3L, 4L, 5L, 2L, 1L));

        assertThat(ConfigurationMapper.toResponse(configuration, SKILLS).skills()).extracting(SkillSummary::slug)
                .containsExactly("zulu", "alpha", "java", "postgresql", "spring-boot");
    }

    @Test
    void aSkillThatIsMissingFromTheCatalogMapIsABug() {
        assertThatThrownBy(() -> ConfigurationMapper.toResponse(configuration(List.of(1L, 99L)), SKILLS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill 99");
    }

    @Test
    void theJsonHasExactlyTheDocumentedProperties() {
        JsonNode tree = TestJson.applicationMapper().valueToTree(ConfigurationMapper.toResponse(configuration(List.of(1L)), SKILLS));

        assertThat(tree.propertyNames()).containsExactlyInAnyOrder(
                "id", "role", "experienceLevel", "difficulty", "durationMinutes", "interviewType", "skills", "codingLanguage",
                "useResume", "strategy", "stageMix", "createdAt");
        assertThat(tree.get("createdAt").asString()).isEqualTo("2026-10-05T12:00:00Z");
    }
}
