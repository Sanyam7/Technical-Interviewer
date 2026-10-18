package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.api.dto.ProgressResponse;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ProgressCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private static InterviewMemory memoryWith(Section... started) {
        Map<Section, SectionProgress> progress = new LinkedHashMap<>();
        for (Section section : started) {
            progress.put(section, new SectionProgress(1, NOW));
        }
        return new InterviewMemory(Map.of(), List.of(), List.of(), List.of(), Map.of(), progress, null);
    }

    // ---- the section -----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"INTRODUCTION,INTRODUCTION", "QUESTIONING,QUESTIONING", "CODING,CODING", "SYSTEM_DESIGN,SYSTEM_DESIGN"})
    void aSectionStateIsInItsOwnSection(InterviewState state, Section section) {
        assertThat(ProgressCalculator.sectionOf(state, null, InterviewMemory.empty())).contains(section);
    }

    @ParameterizedTest
    @CsvSource({"QUESTIONING,QUESTIONING", "CODING,CODING", "SYSTEM_DESIGN,SYSTEM_DESIGN"})
    void aFollowUpIsInTheSectionItsThreadReturnsTo(InterviewState resume, Section section) {
        assertThat(ProgressCalculator.sectionOf(InterviewState.FOLLOW_UP, resume, InterviewMemory.empty())).contains(section);
    }

    @Test
    void aFollowUpWithoutAResumeStateIsABug() {
        assertThatThrownBy(() -> ProgressCalculator.sectionOf(InterviewState.FOLLOW_UP, null, InterviewMemory.empty()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anInterviewThatWasNotStartedIsInNoSection() {
        assertThat(ProgressCalculator.sectionOf(InterviewState.INIT, null, InterviewMemory.empty())).isEmpty();
        assertThat(ProgressCalculator.sectionOf(InterviewState.ABANDONED, null, InterviewMemory.empty())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"FINAL_EVALUATION", "COMPLETED"})
    void afterTheQuestionPhaseTheSectionIsTheLastOneThatWasStarted(InterviewState state) {
        assertThat(ProgressCalculator.sectionOf(state, null, memoryWith(Section.INTRODUCTION, Section.QUESTIONING))).contains(Section.QUESTIONING);
        assertThat(ProgressCalculator.sectionOf(state, null, memoryWith(Section.SYSTEM_DESIGN, Section.INTRODUCTION, Section.CODING)))
                .contains(Section.SYSTEM_DESIGN);
        assertThat(ProgressCalculator.sectionOf(state, null, memoryWith(Section.INTRODUCTION))).contains(Section.INTRODUCTION);
        assertThat(ProgressCalculator.sectionOf(state, null, InterviewMemory.empty())).isEmpty();
    }

    // ---- the time ---------------------------------------------------------------------------------------------------

    @Test
    void beforeTheStartTheWholeConfiguredDurationRemains() {
        assertThat(ProgressCalculator.remainingSeconds(InterviewState.INIT, null, 45, NOW)).isEqualTo(2700);
        assertThat(ProgressCalculator.remainingSeconds(InterviewState.INIT, null, 90, NOW)).isEqualTo(5400);
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INTRODUCTION", "QUESTIONING", "FOLLOW_UP", "CODING", "SYSTEM_DESIGN"})
    void whileQuestionsAreAskedTheTimeToTheDeadlineRemains(InterviewState state) {
        assertThat(ProgressCalculator.remainingSeconds(state, NOW.plusSeconds(2030), 45, NOW)).isEqualTo(2030);
        assertThat(ProgressCalculator.remainingSeconds(state, NOW.plusSeconds(1), 45, NOW)).isEqualTo(1);
        assertThat(ProgressCalculator.remainingSeconds(state, NOW, 45, NOW)).isZero();
    }

    @Test
    void timeNeverGoesNegativeInTheGracePeriod() {
        assertThat(ProgressCalculator.remainingSeconds(InterviewState.QUESTIONING, NOW.minusSeconds(20), 45, NOW)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"FINAL_EVALUATION", "COMPLETED", "ABANDONED"})
    void whenTheQuestionPhaseIsOverNoTimeRemainsEvenIfTheDeadlineIsFarAway(InterviewState state) {
        assertThat(ProgressCalculator.remainingSeconds(state, NOW.plusSeconds(3000), 45, NOW)).isZero();
        assertThat(ProgressCalculator.remainingSeconds(state, null, 45, NOW)).isZero();
    }

    @Test
    void anOpenInterviewWithoutADeadlineIsABug() {
        assertThatThrownBy(() -> ProgressCalculator.remainingSeconds(InterviewState.QUESTIONING, null, 45, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- the counts ---------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"INTRODUCTION,0,4,1", "QUESTIONING,1,4,6", "CODING,2,4,1", "SYSTEM_DESIGN,3,4,1"})
    void theCountsAreThoseOfTheSectionInThePlan(Section section, int index, int count, int planned) {
        InterviewPlan plan = Fixtures.plan();

        ProgressResponse progress = ProgressCalculator.progress(Optional.of(section), plan, 3, 2030);

        assertThat(progress).isEqualTo(new ProgressResponse(section, index, count, 3, planned, 2030));
    }

    @Test
    void withoutASectionOnlyTheTimeAndTheSectionCountAreKnown() {
        ProgressResponse progress = ProgressCalculator.progress(Optional.empty(), Fixtures.plan(), 0, 2700);

        assertThat(progress).isEqualTo(new ProgressResponse(null, null, 4, 0, null, 2700));
    }

    @Test
    void theIndexIsThePositionInThePlanNotTheOrdinalOfTheSection() {
        InterviewPlan codingOnly = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 3, 1),
                new SectionPlan(Section.CODING, 30, 2)),
                new DifficultyBand(3, 2, 4), 2);

        ProgressResponse progress = ProgressCalculator.progress(Optional.of(Section.CODING), codingOnly, 1, 100);

        assertThat(progress.sectionIndex()).isEqualTo(1);
        assertThat(progress.sectionCount()).isEqualTo(2);
        assertThat(progress.plannedQuestionsInSection()).isEqualTo(2);
    }

    @Test
    void aSectionThatIsNotInThePlanIsABug() {
        InterviewPlan introOnly = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 3, 1)),
                new DifficultyBand(3, 2, 4), 2);

        assertThatThrownBy(() -> ProgressCalculator.progress(Optional.of(Section.CODING), introOnly, 0, 0))
                .isInstanceOf(IllegalStateException.class);
    }
}
