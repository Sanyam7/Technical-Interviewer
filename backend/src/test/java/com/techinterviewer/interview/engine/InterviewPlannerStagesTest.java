package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.StageMix;
import com.techinterviewer.interview.domain.StagePlan;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** How the planner divides the questioning slots between the stages (AI_ARCHITECTURE.md section 2.15). */
class InterviewPlannerStagesTest {

    private static PlannerSkill skill(String slug) {
        return new PlannerSkill(slug, List.of(new PlannerTopic("a", 1, 5, 1), new PlannerTopic("b", 1, 5, 1)));
    }

    private static final List<PlannerSkill> FOUR = List.of(skill("java"), skill("react"), skill("spring-boot"), skill("typescript"));

    private static SectionPlan questioning(InterviewStrategy strategy, StageMix mix, ExperienceLevel experience, int minutes, List<PlannerSkill> skills) {
        InterviewPlan plan = InterviewPlanner.plan(new PlannerInput(TargetRole.FULL_STACK, experience, InterviewDifficulty.MEDIUM, minutes,
                InterviewType.TECHNICAL, skills, 2, 4, List.of(), strategy, mix));
        return plan.section(Section.QUESTIONING).orElseThrow();
    }

    private static SectionPlan questioning(InterviewStrategy strategy, int minutes) {
        return questioning(strategy, strategy.preset(), ExperienceLevel.MID, minutes, FOUR);
    }

    @Test
    void aThirtyMinuteFundamentalsFirstInterviewAsksOneFundamentalsQuestionPerTechnologyThenAScenarioThenAProject() {
        SectionPlan plan = questioning(InterviewStrategy.FUNDAMENTALS_FIRST, 30);

        assertThat(plan.slots()).isEqualTo(6);
        assertThat(plan.stages()).containsExactly(
                new StagePlan(InterviewStage.FUNDAMENTALS, 4), new StagePlan(InterviewStage.PRACTICAL, 1), new StagePlan(InterviewStage.PROJECT, 1));
    }

    @Test
    void aResumeFocusedInterviewDiscussesProjectsBeforeScenarios() {
        SectionPlan plan = questioning(InterviewStrategy.RESUME_FOCUSED, 30);

        assertThat(plan.stages()).extracting(StagePlan::stage)
                .containsExactly(InterviewStage.FUNDAMENTALS, InterviewStage.PROJECT, InterviewStage.PRACTICAL);
        assertThat(plan.slotsOf(InterviewStage.PROJECT)).isGreaterThan(plan.slots() / 2);
    }

    @Test
    void aBalancedInterviewSharesTheQuestionsEvenly() {
        SectionPlan plan = questioning(InterviewStrategy.BALANCED, 30);

        assertThat(plan.stages()).containsExactly(
                new StagePlan(InterviewStage.FUNDAMENTALS, 2), new StagePlan(InterviewStage.PRACTICAL, 2), new StagePlan(InterviewStage.PROJECT, 2));
    }

    @ParameterizedTest
    @EnumSource(InterviewStrategy.class)
    void theStageSlotsAlwaysAddUpToTheSectionsSlotsInTheStrategysOrder(InterviewStrategy strategy) {
        StageMix mix = strategy.preset() == null ? new StageMix(34, 33, 33) : strategy.preset();
        for (ExperienceLevel experience : ExperienceLevel.values()) {
            for (int minutes : List.of(15, 30, 45, 60, 90)) {
                SectionPlan plan = questioning(strategy, mix, experience, minutes, FOUR);
                assertThat(plan.stages().stream().mapToInt(StagePlan::slots).sum()).as(strategy + " " + experience + " " + minutes)
                        .isEqualTo(plan.slots());
                assertThat(plan.stages()).extracting(StagePlan::stage).isEqualTo(strategy.order());
            }
        }
    }

    @Test
    void aJuniorGetsMoreFundamentalsAndASeniorMoreApplication() {
        assertThat(InterviewPlanner.adjusted(InterviewStrategy.FUNDAMENTALS_FIRST, new StageMix(60, 25, 15), ExperienceLevel.JUNIOR))
                .isEqualTo(new StageMix(70, 25, 5));
        assertThat(InterviewPlanner.adjusted(InterviewStrategy.FUNDAMENTALS_FIRST, new StageMix(60, 25, 15), ExperienceLevel.MID))
                .isEqualTo(new StageMix(60, 25, 15));
        assertThat(InterviewPlanner.adjusted(InterviewStrategy.FUNDAMENTALS_FIRST, new StageMix(60, 25, 15), ExperienceLevel.STAFF))
                .isEqualTo(new StageMix(50, 35, 15));
        assertThat(InterviewPlanner.adjusted(InterviewStrategy.BALANCED, new StageMix(40, 30, 30), ExperienceLevel.INTERN))
                .isEqualTo(new StageMix(50, 30, 20));
    }

    @Test
    void aCustomMixIsTakenAsGivenWhateverTheSeniority() {
        StageMix mix = new StageMix(0, 100, 0);

        assertThat(InterviewPlanner.adjusted(InterviewStrategy.CUSTOM, mix, ExperienceLevel.JUNIOR)).isEqualTo(mix);
        assertThat(questioning(InterviewStrategy.CUSTOM, mix, ExperienceLevel.JUNIOR, 30, FOUR).stages()).containsExactly(
                new StagePlan(InterviewStage.FUNDAMENTALS, 0), new StagePlan(InterviewStage.PRACTICAL, 6), new StagePlan(InterviewStage.PROJECT, 0));
    }

    @Test
    void fundamentalsFirstGivesEveryTechnologyAFundamentalsQuestionWhenTheSlotsAllow() {
        // A senior's preset (50/25+10/15) would give the four technologies only three fundamentals questions in 30 minutes; the
        // missing one is taken from the last stage that has a slot.
        SectionPlan senior = questioning(InterviewStrategy.FUNDAMENTALS_FIRST, new StageMix(60, 25, 15), ExperienceLevel.SENIOR, 30, FOUR);
        assertThat(senior.stages()).containsExactly(
                new StagePlan(InterviewStage.FUNDAMENTALS, 4), new StagePlan(InterviewStage.PRACTICAL, 2), new StagePlan(InterviewStage.PROJECT, 0));

        // With fewer slots than technologies every slot is fundamentals.
        SectionPlan short_ = questioning(InterviewStrategy.FUNDAMENTALS_FIRST, 15);
        assertThat(short_.stages()).containsExactly(
                new StagePlan(InterviewStage.FUNDAMENTALS, 3), new StagePlan(InterviewStage.PRACTICAL, 0), new StagePlan(InterviewStage.PROJECT, 0));
    }

    @Test
    void theOtherStrategiesFollowTheirSharesEvenWhenSomeTechnologyGetsNoFundamentalsQuestion() {
        SectionPlan resumeFocused = questioning(InterviewStrategy.RESUME_FOCUSED, 30);

        assertThat(resumeFocused.slotsOf(InterviewStage.FUNDAMENTALS)).isEqualTo(1);
    }

    @Test
    void sectionsWithoutQuestioningHaveNoStages() {
        InterviewPlan plan = InterviewPlanner.plan(new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 45,
                InterviewType.FULL_LOOP, FOUR, 2));

        assertThat(plan.version()).isEqualTo(InterviewPlan.CURRENT_VERSION);
        assertThat(plan.section(Section.QUESTIONING).orElseThrow().staged()).isTrue();
        assertThat(plan.sections()).filteredOn(section -> section.section() != Section.QUESTIONING)
                .allSatisfy(section -> assertThat(section.stages()).isEmpty());
    }
}
