package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.domain.ResumePlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResumeAnchoringTest {

    private static final ResumePlan PLAN = new ResumePlan(3,
            Map.of("java/concurrency", List.of("x4", "x1")),
            Map.of("java", List.of("x1", "x2"), "postgresql", List.of("x3")));

    @Test
    void theTopicsOwnExperiencesComeFirstThenTheSkills() {
        assertThat(ResumeAnchoring.pick(PLAN, List.of(), "java/concurrency")).hasValueSatisfying(anchor -> {
            assertThat(anchor.itemId()).isEqualTo("x4");
            assertThat(anchor.basis()).isEqualTo("linked to the topic, résumé question 1 of 3");
        });
        assertThat(ResumeAnchoring.pick(PLAN, List.of("x4", "x1"), "java/concurrency")).map(ResumeAnchoring.Anchor::itemId).hasValue("x2");
        assertThat(ResumeAnchoring.pick(PLAN, List.of(), "java/collections")).map(ResumeAnchoring.Anchor::itemId).hasValue("x1");
        assertThat(ResumeAnchoring.pick(PLAN, List.of(), "postgresql/indexing")).map(ResumeAnchoring.Anchor::basis)
                .hasValue("linked to the skill, résumé question 1 of 3");
    }

    @Test
    void nothingIsAnchoredWithoutAPlanBeyondItsBudgetOrWithoutAnUnusedExperience() {
        assertThat(ResumeAnchoring.pick(null, List.of(), "java/concurrency")).isEmpty();
        assertThat(ResumeAnchoring.pick(PLAN, List.of("x7", "x8", "x9"), "java/concurrency")).isEmpty();
        assertThat(ResumeAnchoring.pick(PLAN, List.of("x3"), "postgresql/indexing")).isEmpty();
        assertThat(ResumeAnchoring.pick(PLAN, List.of(), "react/hooks")).isEmpty();
    }
}
