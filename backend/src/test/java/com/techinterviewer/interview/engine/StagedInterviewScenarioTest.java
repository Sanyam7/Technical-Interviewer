package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.engine.InterviewSimulation.scored;
import static com.techinterviewer.interview.engine.InterviewSimulation.scoredWithClaims;
import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.StageMix;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.engine.InterviewSimulation.Answer;
import com.techinterviewer.interview.engine.InterviewSimulation.Asked;
import com.techinterviewer.interview.engine.InterviewSimulation.Result;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Whole interviews played through the planner and the engine with the stages of AI_ARCHITECTURE.md section 2.15: a candidate who
 * listed Java, Spring Boot, React and TypeScript and uploaded a résumé, interviewed for 30 minutes at mid level.
 */
class StagedInterviewScenarioTest {

    private static final PlannerSkill JAVA = new PlannerSkill("java", List.of(
            new PlannerTopic("collections", 1, 4, 1.5), new PlannerTopic("core-language-oop", 1, 3, 1.4),
            new PlannerTopic("concurrency", 2, 5, 1.3), new PlannerTopic("generics", 2, 4, 0.9)));
    private static final PlannerSkill SPRING = new PlannerSkill("spring-boot", List.of(
            new PlannerTopic("dependency-injection-proxies", 1, 4, 1.5), new PlannerTopic("web-mvc-rest", 1, 4, 1.4),
            new PlannerTopic("transactions", 2, 5, 1.3), new PlannerTopic("data-jpa", 2, 5, 1.2)));
    private static final PlannerSkill REACT = new PlannerSkill("react", List.of(
            new PlannerTopic("hooks", 1, 4, 1.5), new PlannerTopic("rendering-reconciliation", 2, 5, 1.4),
            new PlannerTopic("components-props-state", 1, 3, 1.3)));
    private static final PlannerSkill TYPESCRIPT = new PlannerSkill("typescript", List.of(
            new PlannerTopic("type-system-basics", 1, 3, 1.5), new PlannerTopic("narrowing-type-guards", 2, 4, 1.3),
            new PlannerTopic("generics", 2, 5, 1.3)));

    /** A résumé whose experiences are about Spring Boot data access, React state and Java concurrency. */
    private static final List<PlannerExperience> RESUME = List.of(
            new PlannerExperience("x1", 0, Map.of("spring-boot/data-jpa", 2, "spring-boot/transactions", 1), Set.of("spring-boot")),
            new PlannerExperience("x2", 0, Map.of("react/hooks", 1), Set.of("react")),
            new PlannerExperience("x3", 1, Map.of("java/concurrency", 1), Set.of("java")));

    private static InterviewPlan plan(InterviewStrategy strategy, StageMix mix, int minutes, List<PlannerExperience> experiences) {
        return InterviewPlanner.plan(new PlannerInput(TargetRole.FULL_STACK, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, minutes,
                InterviewType.TECHNICAL, List.of(JAVA, SPRING, REACT, TYPESCRIPT), 2, 4, experiences, strategy, mix));
    }

    private static InterviewPlan plan(InterviewStrategy strategy, int minutes) {
        return plan(strategy, strategy.preset(), minutes, RESUME);
    }

    private static Map<String, TopicRange> ranges(InterviewPlan plan) {
        return new PlannerInput(TargetRole.FULL_STACK, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 30, InterviewType.TECHNICAL,
                List.of(JAVA, SPRING, REACT, TYPESCRIPT), 2).topicRanges();
    }

    /** A solid candidate who mentions their own work in every answer: the claims an interviewer could chase. */
    private static Result play(InterviewPlan plan, int minutes) {
        return InterviewSimulation.play(plan, ranges(plan), Duration.ofMinutes(minutes), question ->
                Answer.of(scoredWithClaims(78, List.of("At my last job I built " + question.sequence() + " services with this"))));
    }

    private static List<Asked> roots(Result result) {
        return result.roots(Section.QUESTIONING);
    }

    @Test
    void theDefaultInterviewAsksEveryTechnologysFundamentalsBeforeAnythingElse() {
        Result result = play(plan(InterviewStrategy.FUNDAMENTALS_FIRST, 30), 30);
        List<Asked> roots = roots(result);

        assertThat(roots).as(result.transcript()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(roots.subList(0, 4)).as(result.transcript())
                .allSatisfy(question -> assertThat(question.type().stage()).isEqualTo(InterviewStage.FUNDAMENTALS))
                .extracting(question -> TopicSelector.skillOf(question.topic()))
                .containsExactlyInAnyOrder("java", "spring-boot", "react", "typescript");
        assertThat(roots.getFirst().type()).isEqualTo(QuestionType.INTERNALS);
    }

    @Test
    void theStagesRunInOrderAndTheResumeIsUsedOnlyInTheProjectStage() {
        Result result = play(plan(InterviewStrategy.FUNDAMENTALS_FIRST, 60), 60);
        List<InterviewStage> stages = roots(result).stream().map(question -> question.type().stage()).toList();

        assertThat(stages).as(result.transcript()).isSortedAccordingTo(java.util.Comparator.comparingInt(InterviewStage::ordinal));
        assertThat(stages).as(result.transcript()).contains(InterviewStage.FUNDAMENTALS, InterviewStage.PRACTICAL, InterviewStage.PROJECT);
        assertThat(result.asked()).as(result.transcript())
                .filteredOn(question -> question.resumeItem() != null)
                .isNotEmpty()
                .allSatisfy(question -> assertThat(question.type()).isEqualTo(QuestionType.EXPERIENCE));
    }

    @Test
    void practicalQuestionsAlternateScenarioAndDebugging() {
        InterviewPlan plan = plan(InterviewStrategy.CUSTOM, new StageMix(40, 60, 0), 60, RESUME);
        Result result = play(plan, 60);
        List<QuestionType> practical = roots(result).stream().map(Asked::type).filter(type -> type.stage() == InterviewStage.PRACTICAL).toList();

        assertThat(practical).as(result.transcript()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(practical.subList(0, 3)).containsExactly(QuestionType.SCENARIO, QuestionType.DEBUGGING, QuestionType.SCENARIO);
        assertThat(result.asked()).allSatisfy(question -> assertThat(question.resumeItem()).isNull());
    }

    @Test
    void aClaimIsNeverChasedFromAFundamentalsOrPracticalQuestion() {
        // Every answer claims experience, and the default strategy still probes no claim until the candidate's own work is the subject.
        Result result = play(plan(InterviewStrategy.FUNDAMENTALS_FIRST, 60), 60);

        assertThat(result.turns()).as(result.transcript())
                .filteredOn(turn -> turn.decision().pendingStep() != null
                        && turn.decision().pendingStep().followUpMode() == FollowUpMode.PROBE_CLAIM)
                .allSatisfy(turn -> assertThat(turn.question().type()).isEqualTo(QuestionType.EXPERIENCE));
    }

    @Test
    void aResumeFocusedInterviewIsMostlyAboutTheCandidatesOwnWorkAfterAShortBaseline() {
        Result result = play(plan(InterviewStrategy.RESUME_FOCUSED, 30), 30);
        List<Asked> roots = roots(result);

        assertThat(roots.getFirst().type().stage()).as(result.transcript()).isEqualTo(InterviewStage.FUNDAMENTALS);
        assertThat(roots.stream().filter(question -> question.type() == QuestionType.EXPERIENCE).count()).as(result.transcript())
                .isGreaterThan(roots.size() / 2);
    }

    @Test
    void withoutAResumeOrAnythingTheCandidateSaidAProjectSlotBecomesAScenario() {
        InterviewPlan plan = plan(InterviewStrategy.CUSTOM, new StageMix(50, 0, 50), 30, List.of());
        Result result = InterviewSimulation.play(plan, ranges(plan), Duration.ofMinutes(30), question -> Answer.of(scored(70)));

        assertThat(roots(result)).as(result.transcript()).filteredOn(question -> question.type().stage() != InterviewStage.FUNDAMENTALS)
                .isNotEmpty()
                .allSatisfy(question -> assertThat(question.type()).isEqualTo(QuestionType.SCENARIO));
    }

    @Test
    void aStrongFundamentalsAnswerIsDeepenedOnItsOwnSubject() {
        Result result = InterviewSimulation.play(plan(InterviewStrategy.FUNDAMENTALS_FIRST, 60), ranges(null), Duration.ofMinutes(60),
                question -> Answer.of(scored(92)));

        List<Asked> deepened = result.followUps().stream().filter(question -> question.mode() == FollowUpMode.DEEPEN).toList();
        assertThat(deepened).as(result.transcript()).isNotEmpty();
        for (Asked followUp : deepened) {
            Asked parent = result.asked().get(followUp.sequence() - 2);
            assertThat(followUp.topic()).isEqualTo(parent.topic());
            assertThat(followUp.type()).isEqualTo(parent.type());
        }
    }

    @Test
    void theSameConfigurationAlwaysPlansAndAsksTheSameInterview() {
        Result first = play(plan(InterviewStrategy.FUNDAMENTALS_FIRST, 45), 45);
        Result second = play(plan(InterviewStrategy.FUNDAMENTALS_FIRST, 45), 45);

        assertThat(first.plan()).isEqualTo(second.plan());
        assertThat(IntStream.range(0, first.asked().size()).mapToObj(i -> first.asked().get(i).topic() + " " + first.asked().get(i).type()).toList())
                .isEqualTo(IntStream.range(0, second.asked().size()).mapToObj(i -> second.asked().get(i).topic() + " " + second.asked().get(i).type()).toList());
        assertThat(first.asked()).filteredOn(question -> question.kind() == QuestionKind.TECHNICAL).isNotEmpty();
    }
}
