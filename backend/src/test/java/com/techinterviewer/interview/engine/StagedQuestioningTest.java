package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.ResumePlan;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.StagePlan;
import com.techinterviewer.interview.domain.TopicStat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The rules of {@link StagedQuestioning}, one at a time (AI_ARCHITECTURE.md section 2.15). */
class StagedQuestioningTest {

    private static final String COLLECTIONS = "java/collections";
    private static final String HOOKS = "react/hooks";
    private static final Map<String, TopicRange> RANGES = Map.of(COLLECTIONS, new TopicRange(1, 5), HOOKS, new TopicRange(1, 5));

    private static InterviewPlan plan(DifficultyBand band, @Nullable ResumePlan resume, StagePlan... stages) {
        int slots = java.util.Arrays.stream(stages).mapToInt(StagePlan::slots).sum();
        return new InterviewPlan(InterviewPlan.CURRENT_VERSION, List.of(
                new SectionPlan(Section.INTRODUCTION, 2, 1),
                new SectionPlan(Section.QUESTIONING, 4 * slots, slots,
                        slots == 1 ? Map.of(COLLECTIONS, 1) : Map.of(COLLECTIONS, slots - slots / 2, HOOKS, slots / 2), List.of(stages))),
                band, 2, 4, resume);
    }

    private static InterviewPlan plan(StagePlan... stages) {
        return plan(new DifficultyBand(3, 2, 4), null, stages);
    }

    private static StagePlan stage(InterviewStage stage, int slots) {
        return new StagePlan(stage, slots);
    }

    private static InterviewMemory decided(InterviewStage stage, int times) {
        InterviewMemory memory = InterviewMemory.empty();
        for (int i = 0; i < times; i++) {
            memory = memory.withStageQuestion(stage);
        }
        return memory;
    }

    @Test
    void theFirstStageWithASlotLeftIsAsked() {
        InterviewPlan plan = plan(stage(InterviewStage.FUNDAMENTALS, 2), stage(InterviewStage.PRACTICAL, 1));

        assertThat(StagedQuestioning.next(plan, RANGES, decided(InterviewStage.FUNDAMENTALS, 1)).stage()).isEqualTo(InterviewStage.FUNDAMENTALS);
        assertThat(StagedQuestioning.next(plan, RANGES, decided(InterviewStage.FUNDAMENTALS, 2)).stage()).isEqualTo(InterviewStage.PRACTICAL);
    }

    @Test
    void aStageWithoutSlotsIsSkipped() {
        InterviewPlan plan = plan(stage(InterviewStage.FUNDAMENTALS, 0), stage(InterviewStage.PRACTICAL, 2), stage(InterviewStage.PROJECT, 0));

        StagedQuestioning.Choice choice = StagedQuestioning.next(plan, RANGES, InterviewMemory.empty());

        assertThat(choice.stage()).isEqualTo(InterviewStage.PRACTICAL);
        assertThat(choice.step().questionType()).isEqualTo(QuestionType.SCENARIO);
    }

    @Test
    void fundamentalsAreConceptsAtTheJuniorLevelsAndAlternateInternalsAndConceptsAbove() {
        InterviewPlan junior = plan(new DifficultyBand(2, 1, 3), null, stage(InterviewStage.FUNDAMENTALS, 3));
        InterviewPlan mid = plan(stage(InterviewStage.FUNDAMENTALS, 3));

        assertThat(StagedQuestioning.next(junior, RANGES, InterviewMemory.empty()).step().questionType()).isEqualTo(QuestionType.CONCEPT);
        assertThat(StagedQuestioning.next(mid, RANGES, decided(InterviewStage.FUNDAMENTALS, 0)).step().questionType()).isEqualTo(QuestionType.INTERNALS);
        assertThat(StagedQuestioning.next(mid, RANGES, decided(InterviewStage.FUNDAMENTALS, 1)).step().questionType()).isEqualTo(QuestionType.CONCEPT);
        assertThat(StagedQuestioning.next(mid, RANGES, decided(InterviewStage.FUNDAMENTALS, 2)).step().questionType()).isEqualTo(QuestionType.INTERNALS);
    }

    @Test
    void aProjectQuestionIsAnchoredOnAResumeExperienceLinkedToItsTopicAndPrefersSuchATopic() {
        ResumePlan resume = new ResumePlan(1, Map.of(HOOKS, List.of("x2")), Map.of("react", List.of("x2")));
        InterviewPlan plan = plan(new DifficultyBand(3, 2, 4), resume, stage(InterviewStage.FUNDAMENTALS, 1), stage(InterviewStage.PROJECT, 1));

        StagedQuestioning.Choice choice = StagedQuestioning.next(plan, RANGES, decided(InterviewStage.FUNDAMENTALS, 1));

        assertThat(choice.stage()).isEqualTo(InterviewStage.PROJECT);
        assertThat(choice.step().topic()).isEqualTo(HOOKS);
        assertThat(choice.step().questionType()).isEqualTo(QuestionType.EXPERIENCE);
        assertThat(choice.step().resumeItemId()).isEqualTo("x2");
        assertThat(choice.rationale()).contains("anchored on résumé experience x2");
    }

    @Test
    void aFundamentalsQuestionIsNeverAnchoredEvenWhenTheResumeShowsItsTopic() {
        ResumePlan resume = new ResumePlan(1, Map.of(COLLECTIONS, List.of("x1")), Map.of("java", List.of("x1")));
        InterviewPlan plan = plan(new DifficultyBand(3, 2, 4), resume, stage(InterviewStage.FUNDAMENTALS, 1), stage(InterviewStage.PROJECT, 1));

        StagedQuestioning.Choice choice = StagedQuestioning.next(plan, RANGES, InterviewMemory.empty());

        assertThat(choice.stage()).isEqualTo(InterviewStage.FUNDAMENTALS);
        assertThat(choice.step().resumeItemId()).isNull();
    }

    @Test
    void withoutAResumeAProjectQuestionIsAboutWhatTheCandidateSaidAndWithoutThatItIsAScenario() {
        InterviewPlan plan = plan(stage(InterviewStage.PROJECT, 1));
        InterviewMemory saidSomething = InterviewMemory.empty()
                .withCandidateClaims(List.of(new Claim("I built the checkout service with Spring Boot", false, UUID.randomUUID())));

        StagedQuestioning.Choice claims = StagedQuestioning.next(plan, RANGES, saidSomething);
        StagedQuestioning.Choice nothing = StagedQuestioning.next(plan, RANGES, InterviewMemory.empty());

        assertThat(claims.step().questionType()).isEqualTo(QuestionType.EXPERIENCE);
        assertThat(claims.step().resumeItemId()).isNull();
        assertThat(claims.rationale()).contains("what the candidate said of their own work");
        assertThat(nothing.stage()).isEqualTo(InterviewStage.PROJECT);
        assertThat(nothing.step().questionType()).isEqualTo(QuestionType.SCENARIO);
        assertThat(nothing.rationale()).contains("asked as a SCENARIO");
    }

    @Test
    void theTopicAndLevelFollowTheUsualSelection() {
        InterviewPlan plan = plan(stage(InterviewStage.FUNDAMENTALS, 4));
        InterviewMemory memory = new InterviewMemory(Map.of(COLLECTIONS, new TopicStat(1, 85, 4, 85)), List.of(), List.of(), List.of(),
                Map.of(), Map.of(), null);

        StagedQuestioning.Choice choice = StagedQuestioning.next(plan, RANGES, memory);

        assertThat(choice.step().topic()).isEqualTo(HOOKS);
        assertThat(choice.step().difficulty()).isEqualTo(3);
        assertThat(choice.rationale()).startsWith("QUESTIONING FUNDAMENTALS question 1 of 4: ").contains("react/hooks at level 3 (MEDIUM)");
    }

    @Test
    void aPlanWithoutStagesIsNotForStagedQuestioning() {
        InterviewPlan plain = new InterviewPlan(1, List.of(new SectionPlan(Section.INTRODUCTION, 2, 1),
                new SectionPlan(Section.QUESTIONING, 8, 2, Map.of(COLLECTIONS, 2))), new DifficultyBand(3, 2, 4), 2);

        assertThatThrownBy(() -> StagedQuestioning.next(plain, RANGES, InterviewMemory.empty())).isInstanceOf(IllegalArgumentException.class);
    }
}
