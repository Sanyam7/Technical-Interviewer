package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.catalog.PlannerTopic;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.engine.PlannerInput;
import com.techinterviewer.interview.engine.PlannerSkill;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlannerInputsTest {

    private static final Map<Long, SkillRef> SKILLS = Map.of(
            1L, new SkillRef(1, "java", "Java"),
            2L, new SkillRef(2, "spring-boot", "Spring Boot"),
            3L, new SkillRef(3, "retired", "Retired"));

    private static InterviewConfiguration configuration(List<Long> skillIds) {
        return InterviewConfiguration.create(
                UUID.randomUUID(), TargetRole.BACKEND, ExperienceLevel.JUNIOR, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, null, false, skillIds, Instant.parse("2026-10-05T12:00:00Z"));
    }

    @Test
    void theConfigurationIsCarriedOverFieldByField() {
        PlannerInput input = PlannerInputs.of(configuration(List.of(1L)), SKILLS, List.of(), 1, 4);

        assertThat(input.role()).isEqualTo(TargetRole.BACKEND);
        assertThat(input.experience()).isEqualTo(ExperienceLevel.JUNIOR);
        assertThat(input.difficulty()).isEqualTo(InterviewDifficulty.EASY);
        assertThat(input.durationMinutes()).isEqualTo(30);
        assertThat(input.type()).isEqualTo(InterviewType.TECHNICAL);
        assertThat(input.maxFollowUpDepth()).isEqualTo(1);
    }

    @Test
    void topicsAreGroupedUnderTheirSkillAndKeepTheirRangeAndWeight() {
        List<PlannerTopic> topics = List.of(
                new PlannerTopic("java", "Java", "collections", "Collections", 1, 4, 1.5),
                new PlannerTopic("spring-boot", "Spring Boot", "security", "Spring Security", 2, 5, 2.0),
                new PlannerTopic("java", "Java", "generics", "Generics", 2, 4, 1.0));

        PlannerInput input = PlannerInputs.of(configuration(List.of(2L, 1L)), SKILLS, topics, 2, 4);

        assertThat(input.skills()).extracting(PlannerSkill::slug).containsExactly("java", "spring-boot");
        PlannerSkill java = input.skills().get(0);
        assertThat(java.topics()).extracting(topic -> topic.slug()).containsExactly("collections", "generics");
        assertThat(java.topics().get(0).minDifficulty()).isEqualTo(1);
        assertThat(java.topics().get(0).maxDifficulty()).isEqualTo(4);
        assertThat(java.topics().get(0).weight()).isEqualTo(1.5);
        assertThat(input.skills().get(1).topics()).extracting(topic -> topic.slug()).containsExactly("security");
    }

    @Test
    void aSkillWithoutPlanningTopicsStillTakesPartWithNoTopics() {
        PlannerInput input = PlannerInputs.of(configuration(List.of(1L, 3L)), SKILLS, List.of(), 2, 4);

        assertThat(input.skills()).extracting(PlannerSkill::slug).containsExactly("java", "retired");
        assertThat(input.skills()).allSatisfy(skill -> assertThat(skill.topics()).isEmpty());
    }
}
