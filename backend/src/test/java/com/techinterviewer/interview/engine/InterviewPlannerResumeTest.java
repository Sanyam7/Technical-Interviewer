package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.ResumePlan;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** How the planner uses the candidate context of a résumé (AI_ARCHITECTURE.md sections 2.13 and 2.15). */
class InterviewPlannerResumeTest {

    private static final PlannerSkill JAVA = new PlannerSkill("java", List.of(
            new PlannerTopic("collections", 1, 4, 1), new PlannerTopic("concurrency", 2, 5, 1), new PlannerTopic("generics", 2, 4, 1),
            new PlannerTopic("streams", 2, 4, 1)));
    private static final PlannerSkill SPRING = new PlannerSkill("spring-boot", List.of(
            new PlannerTopic("data-jpa", 2, 5, 1), new PlannerTopic("security", 2, 5, 1), new PlannerTopic("testing", 2, 4, 1)));

    private static PlannerInput input(int minutes, InterviewType type, List<PlannerExperience> experiences) {
        return new PlannerInput(TargetRole.FULL_STACK, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, minutes, type, List.of(JAVA, SPRING), 2, 4,
                experiences);
    }

    private static PlannerInput input(int minutes, List<PlannerExperience> experiences, InterviewStrategy strategy) {
        return new PlannerInput(TargetRole.FULL_STACK, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, minutes, InterviewType.TECHNICAL,
                List.of(JAVA, SPRING), 2, 4, experiences, strategy, strategy.preset());
    }

    private static PlannerExperience experience(String id, int priority, Map<String, Integer> topics, String... skills) {
        return new PlannerExperience(id, priority, topics, Set.of(skills));
    }

    @Test
    void withoutAResumeThePlanIsUnchanged() {
        InterviewPlan plain = InterviewPlanner.plan(input(30, InterviewType.TECHNICAL, List.of()));

        assertThat(plain.resume()).isNull();
        assertThat(plain).isEqualTo(InterviewPlanner.plan(new PlannerInput(TargetRole.FULL_STACK, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 30,
                InterviewType.TECHNICAL, List.of(JAVA, SPRING), 2, 4)));
    }

    @Test
    void theResumeDoesNotShapeTheTopicsOfAFundamentalsFirstInterview() {
        // The topics an interview asks are the catalog's, in the order of their weight; a résumé decides only what the project stage
        // talks about. Linking java/streams to the résumé changes nothing in the quotas.
        List<PlannerExperience> streams = List.of(experience("x1", 0, Map.of("java/streams", 1)));

        Map<String, Integer> plain = InterviewPlanner.plan(input(30, List.of(), InterviewStrategy.FUNDAMENTALS_FIRST))
                .section(Section.QUESTIONING).orElseThrow().topicQuotas();
        Map<String, Integer> personalised = InterviewPlanner.plan(input(30, streams, InterviewStrategy.FUNDAMENTALS_FIRST))
                .section(Section.QUESTIONING).orElseThrow().topicQuotas();

        assertThat(personalised).isEqualTo(plain).doesNotContainKey("java/streams");
    }

    @Test
    void inAResumeFocusedInterviewATopicTheResumeShowsWeighsDoubleInItsSkillsQuota() {
        // 30 minutes: 6 questioning slots, 3 for Java over four equal topics. Without the résumé the alphabetically last topic,
        // streams, gets none; linked to the résumé it weighs double and gets one.
        List<PlannerExperience> streams = List.of(experience("x1", 0, Map.of("java/streams", 1)));

        Map<String, Integer> plain = InterviewPlanner.plan(input(30, List.of(), InterviewStrategy.RESUME_FOCUSED))
                .section(Section.QUESTIONING).orElseThrow().topicQuotas();
        Map<String, Integer> personalised = InterviewPlanner.plan(input(30, streams, InterviewStrategy.RESUME_FOCUSED))
                .section(Section.QUESTIONING).orElseThrow().topicQuotas();

        assertThat(plain).doesNotContainKey("java/streams");
        assertThat(personalised).containsEntry("java/streams", 1);
        assertThat(personalised.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(plain.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void onlyTheProjectStageIsAnchoredAndTheLinksAreOrderedByStrengthThenKindThenResumeOrder() {
        List<PlannerExperience> experiences = List.of(
                experience("x1", 2, Map.of("java/concurrency", 1)),
                experience("x2", 0, Map.of("java/concurrency", 1), "java"),
                experience("x3", 1, Map.of("java/concurrency", 2)),
                experience("x4", 0, Map.of(), "spring-boot"),
                experience("x5", 0, Map.of("spring-boot/testing", 1)),
                experience("x6", 0, Map.of("react/hooks", 1)));

        InterviewPlan plan = InterviewPlanner.plan(input(30, InterviewType.TECHNICAL, experiences));
        ResumePlan resume = plan.resume();

        assertThat(resume.questions()).isEqualTo(plan.section(Section.QUESTIONING).orElseThrow().slotsOf(InterviewStage.PROJECT)).isEqualTo(1);
        assertThat(resume.topics().get("java/concurrency")).containsExactly("x3", "x2", "x1");
        assertThat(resume.skills().get("java")).containsExactly("x2", "x3", "x1");
        assertThat(resume.skills().get("spring-boot")).containsExactly("x4", "x5");
        assertThat(resume.topics()).doesNotContainKey("react/hooks");
        assertThat(resume.skills()).doesNotContainKey("react");
    }

    @Test
    void aResumeFocusedInterviewAnchorsMostOfItsQuestions() {
        List<PlannerExperience> experiences = List.of(
                experience("x1", 0, Map.of("java/concurrency", 1)), experience("x2", 0, Map.of("java/collections", 1)),
                experience("x3", 0, Map.of("spring-boot/testing", 1)), experience("x4", 0, Map.of(), "spring-boot"),
                experience("x5", 0, Map.of("java/generics", 1)), experience("x6", 0, Map.of("spring-boot/data-jpa", 1)));

        InterviewPlan plan = InterviewPlanner.plan(input(30, experiences, InterviewStrategy.RESUME_FOCUSED));

        assertThat(plan.section(Section.QUESTIONING).orElseThrow().slotsOf(InterviewStage.PROJECT)).isEqualTo(4);
        assertThat(plan.resume().questions()).isEqualTo(4);
    }

    @Test
    void withoutAProjectStageTheResumeAnchorsNothing() {
        // A 15-minute fundamentals-first interview has three questions, all fundamentals: nothing is left for the résumé.
        InterviewPlan plan = InterviewPlanner.plan(input(15, List.of(experience("x1", 0, Map.of(), "java")), InterviewStrategy.FUNDAMENTALS_FIRST));

        assertThat(plan.section(Section.QUESTIONING).orElseThrow().slotsOf(InterviewStage.PROJECT)).isZero();
        assertThat(plan.resume()).isNull();
    }

    @Test
    void neverMoreAnchoredQuestionsThanLinkableExperiences() {
        InterviewPlan plan = InterviewPlanner.plan(input(60, InterviewType.TECHNICAL, List.of(experience("x1", 0, Map.of(), "java"))));

        assertThat(plan.resume().questions()).isEqualTo(1);
    }

    @Test
    void anInterviewWithoutQuestioningIsNotAnchored() {
        InterviewPlan coding = InterviewPlanner.plan(input(45, InterviewType.CODING, List.of(experience("x1", 0, Map.of(), "java"))));

        assertThat(coding.resume()).isNull();
    }
}
