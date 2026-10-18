package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InterviewPlanTest {

    private static final DifficultyBand BAND = new DifficultyBand(3, 2, 4);

    private static InterviewPlan fullLoop() {
        return new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 4, 1),
                new SectionPlan(Section.QUESTIONING, 24, 6, Map.of("java/concurrency", 3, "postgresql/indexing", 3)),
                new SectionPlan(Section.CODING, 16, 1),
                new SectionPlan(Section.SYSTEM_DESIGN, 14, 1)), BAND, 2);
    }

    @Test
    void navigatesSectionsInPlanOrder() {
        InterviewPlan plan = fullLoop();

        assertThat(plan.firstSection()).isEqualTo(Section.INTRODUCTION);
        assertThat(plan.sectionAfter(Section.INTRODUCTION)).contains(Section.QUESTIONING);
        assertThat(plan.sectionAfter(Section.QUESTIONING)).contains(Section.CODING);
        assertThat(plan.sectionAfter(Section.CODING)).contains(Section.SYSTEM_DESIGN);
        assertThat(plan.sectionAfter(Section.SYSTEM_DESIGN)).isEmpty();
    }

    @Test
    void sectionAfterSkipsSectionsThePlanDoesNotHave() {
        InterviewPlan technicalOnly = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 4, 1), new SectionPlan(Section.QUESTIONING, 26, 6)), BAND, 2);
        InterviewPlan designOnly = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 4, 1), new SectionPlan(Section.SYSTEM_DESIGN, 26, 1)), BAND, 2);

        assertThat(technicalOnly.sectionAfter(Section.QUESTIONING)).isEmpty();
        assertThat(technicalOnly.hasSection(Section.CODING)).isFalse();
        assertThat(designOnly.sectionAfter(Section.INTRODUCTION)).contains(Section.SYSTEM_DESIGN);
        assertThat(designOnly.sectionAfter(Section.CODING)).as("a section outside the plan has no successor").isEmpty();
    }

    @Test
    void lookupAndTotals() {
        InterviewPlan plan = fullLoop();

        assertThat(plan.section(Section.QUESTIONING)).map(SectionPlan::slots).contains(6);
        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas()).containsEntry("java/concurrency", 3);
        assertThat(plan.totalSlots()).isEqualTo(9);
        assertThat(plan.totalMinutes()).isEqualTo(58);
        assertThat(new InterviewPlan(1, List.of(new SectionPlan(Section.CODING, 5, 1)), BAND, 0).section(Section.QUESTIONING))
                .isEqualTo(Optional.empty());
    }

    @Test
    void sectionsMustBeUniqueAndInRunningOrder() {
        SectionPlan intro = new SectionPlan(Section.INTRODUCTION, 4, 1);
        SectionPlan coding = new SectionPlan(Section.CODING, 4, 1);
        SectionPlan questioning = new SectionPlan(Section.QUESTIONING, 4, 1);

        assertThatThrownBy(() -> new InterviewPlan(1, List.of(coding, questioning), BAND, 2)).hasMessageContaining("running order");
        assertThatThrownBy(() -> new InterviewPlan(1, List.of(intro, intro), BAND, 2)).hasMessageContaining("unique");
        assertThatThrownBy(() -> new InterviewPlan(1, List.of(), BAND, 2)).hasMessageContaining("empty");
    }

    @ParameterizedTest(name = "version {0}, depth {1} valid={2}")
    @CsvSource({"1, 0, true", "1, 2, true", "7, 1, true", "0, 1, false", "-1, 1, false", "1, 3, false", "1, -1, false"})
    void versionAndFollowUpDepthLimits(int version, int depth, boolean valid) {
        List<SectionPlan> sections = List.of(new SectionPlan(Section.INTRODUCTION, 4, 1));
        if (valid) {
            assertThat(new InterviewPlan(version, sections, BAND, depth).maxFollowUpDepth()).isEqualTo(depth);
        } else {
            assertThatThrownBy(() -> new InterviewPlan(version, sections, BAND, depth)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void planIsImmutableWhateverItWasBuiltFrom() {
        List<SectionPlan> sections = new ArrayList<>(List.of(new SectionPlan(Section.INTRODUCTION, 4, 1)));
        Map<String, Integer> quotas = new HashMap<>(Map.of("a/b", 1));
        InterviewPlan plan = new InterviewPlan(1, sections, BAND, 2);
        SectionPlan questioning = new SectionPlan(Section.QUESTIONING, 4, 1, quotas);

        sections.add(new SectionPlan(Section.CODING, 4, 1));
        quotas.put("c/d", 2);

        assertThat(plan.sections()).hasSize(1);
        assertThat(questioning.topicQuotas()).containsOnlyKeys("a/b");
        assertThatThrownBy(() -> plan.sections().add(questioning)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> questioning.topicQuotas().put("x/y", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sectionBudgetsAreBounded() {
        assertThatThrownBy(() -> new SectionPlan(Section.CODING, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SectionPlan(Section.CODING, 241, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SectionPlan(Section.CODING, 4, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SectionPlan(Section.CODING, 4, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SectionPlan(Section.QUESTIONING, 4, 1, Map.of("a/b", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SectionPlan(Section.QUESTIONING, 4, 1, Map.of(" ", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SectionPlan(Section.CODING, 0, 0).slots()).isZero();
        assertThat(new SectionPlan(Section.CODING, 240, 50).minutes()).isEqualTo(240);
    }
}
