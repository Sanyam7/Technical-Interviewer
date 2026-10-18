package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IntroductionPromptTest {

    private static String variantOf(String prompt) {
        if (prompt.startsWith("Welcome to your")) {
            return "welcome";
        }
        if (prompt.startsWith("Thanks for joining")) {
            return "thanks";
        }
        if (prompt.startsWith("Before the technical questions")) {
            return "before";
        }
        throw new AssertionError("unknown phrasing: " + prompt);
    }

    @Test
    void theSameConfigurationAlwaysGetsTheSameText() {
        String first = IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of("Java", "PostgreSQL"));

        for (int i = 0; i < 20; i++) {
            assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of("Java", "PostgreSQL"))).isEqualTo(first);
        }
    }

    @Test
    void theTextNamesTheRoleTheSeniorityAndTheSelectedSkills() {
        String prompt = IntroductionPrompt.render(TargetRole.FULL_STACK, ExperienceLevel.SENIOR, List.of("Java", "React", "PostgreSQL"));

        assertThat(prompt).contains("full-stack").contains("senior").contains("Java, React and PostgreSQL");
    }

    @Test
    void everyRoleAndLevelHasItsOwnWording() {
        Set<String> phrases = new HashSet<>();
        for (ExperienceLevel level : ExperienceLevel.values()) {
            String prompt = IntroductionPrompt.render(TargetRole.BACKEND, level, List.of("Java"));
            phrases.add(prompt);
        }
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.INTERN, List.of("Java"))).contains("intern-level");
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.JUNIOR, List.of("Java"))).contains("junior");
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of("Java"))).contains("mid-level");
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.SENIOR, List.of("Java"))).contains("senior");
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.STAFF, List.of("Java"))).contains("staff-level");
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of("Java"))).contains("backend");
        assertThat(IntroductionPrompt.render(TargetRole.FRONTEND, ExperienceLevel.MID, List.of("Java"))).contains("frontend");
        assertThat(IntroductionPrompt.render(TargetRole.FULL_STACK, ExperienceLevel.MID, List.of("Java"))).contains("full-stack");
        assertThat(phrases).hasSize(5);
    }

    @Test
    void skillNamesAreJoinedLikeAnEnglishList() {
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of("Java"))).contains("Java").doesNotContain(" and Java").doesNotContain("Java,");
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of("Java", "Spring Boot"))).contains("Java and Spring Boot");
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of("Java", "Spring Boot", "PostgreSQL", "Redis")))
                .contains("Java, Spring Boot, PostgreSQL and Redis");
    }

    @Test
    void allThreePhrasingsAreInUseAndEachIsStablePerConfiguration() {
        Set<String> variants = new HashSet<>();
        List<String> names = List.of("Java", "Spring Boot", "PostgreSQL", "Redis", "Kafka", "Docker", "React", "TypeScript");
        for (TargetRole role : TargetRole.values()) {
            for (ExperienceLevel level : ExperienceLevel.values()) {
                for (int count = 1; count <= names.size(); count++) {
                    String prompt = IntroductionPrompt.render(role, level, names.subList(0, count));
                    variants.add(variantOf(prompt));
                    assertThat(variantOf(IntroductionPrompt.render(role, level, new ArrayList<>(names.subList(0, count)))))
                            .isEqualTo(variantOf(prompt));
                }
            }
        }
        assertThat(variants).containsExactlyInAnyOrder("welcome", "thanks", "before");
    }

    @Test
    void everyPhrasingAsksForIntroductionAndARecentProject() {
        List<String> names = List.of("Java", "Spring Boot", "PostgreSQL", "Redis", "Kafka", "Docker", "React", "TypeScript");
        Set<String> seen = new HashSet<>();
        for (ExperienceLevel level : ExperienceLevel.values()) {
            for (int count = 1; count <= names.size() && seen.size() < 3; count++) {
                String prompt = IntroductionPrompt.render(TargetRole.BACKEND, level, names.subList(0, count));
                if (seen.add(variantOf(prompt))) {
                    assertThat(prompt).containsAnyOf("recent project", "recent piece of work");
                    assertThat(prompt.length()).isBetween(120, 600);
                }
            }
        }
        assertThat(seen).hasSize(3);
    }

    @Test
    void theInputIsValidated() {
        assertThatThrownBy(() -> IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of()))
                .hasMessageContaining("between 1 and 20");
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add("Skill " + i);
        }
        assertThatThrownBy(() -> IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, tooMany))
                .hasMessageContaining("between 1 and 20");
        assertThat(IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, tooMany.subList(0, 20))).contains("Skill 19");
        assertThatThrownBy(() -> IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntroductionPrompt.render(null, ExperienceLevel.MID, List.of("Java"))).hasMessageContaining("role");
        assertThatThrownBy(() -> IntroductionPrompt.render(TargetRole.BACKEND, null, List.of("Java"))).hasMessageContaining("experience");
        assertThatThrownBy(() -> IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, null)).hasMessageContaining("skillNames");
    }
}
