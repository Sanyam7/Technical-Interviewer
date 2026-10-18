package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.engine.AnsweredQuestion;
import com.techinterviewer.interview.engine.EvaluationOutcome;
import com.techinterviewer.interview.engine.TopicRange;
import com.techinterviewer.interview.engine.TurnInput;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The facts the engine decides a turn from are plain functions of the rows. */
class TurnInputsTest {

    private static final Instant NOW = Fixtures.NOW;
    private static final TopicInfo CONCURRENCY = new TopicInfo(7, 1, "java", "Java", "concurrency", "Concurrency");

    private static Interview started(InterviewPlan plan) {
        Interview interview = Interview.create(UUID.randomUUID(), UUID.randomUUID(), plan, NOW);
        interview.start(NOW, 60);
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, NOW);
        return interview;
    }

    private static InterviewQuestion technical(UUID interview, int difficulty, ExpectedKeyPoint... keyPoints) {
        QuestionContent content = new QuestionContent("Explain volatile.", null, List.of(keyPoints), "technical.v1", QuestionOrigin.GENERATED, null);
        return InterviewQuestion.root(interview, 2, QuestionKind.TECHNICAL, 7L, difficulty, content, NOW.plusSeconds(30));
    }

    @Test
    void theTopicsNeedingARangeAreTheQuotasOfTheQuestioningSectionAndTheAnsweredTopic() {
        Interview interview = started(Fixtures.plan());

        assertThat(TurnInputs.rangeKeys(interview, "java/concurrency")).containsExactlyInAnyOrder("java/concurrency", "postgresql/indexing");
        assertThat(TurnInputs.rangeKeys(interview, "spring-boot/security")).containsExactlyInAnyOrder("java/concurrency", "postgresql/indexing", "spring-boot/security");
        assertThat(TurnInputs.rangeKeys(interview, null)).containsExactlyInAnyOrder("java/concurrency", "postgresql/indexing");
    }

    @Test
    void aPlanWithoutAQuestioningSectionNeedsRangesOnlyForTheAnsweredTopic() {
        InterviewPlan codingOnly = new InterviewPlan(1, List.of(new SectionPlan(Section.INTRODUCTION, 4, 1), new SectionPlan(Section.CODING, 40, 2)),
                new DifficultyBand(3, 2, 4), 2);
        Interview interview = started(codingOnly);

        assertThat(TurnInputs.rangeKeys(interview, null)).isEmpty();
        assertThat(TurnInputs.rangeKeys(interview, "java/concurrency")).containsExactly("java/concurrency");
    }

    @ParameterizedTest
    @EnumSource(value = Section.class)
    void everySectionHasTheStateInWhichItsQuestionsAreAsked(Section section) {
        assertThat(TurnInputs.sectionOf(section.state())).isEqualTo(section);
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INIT", "FOLLOW_UP", "FINAL_EVALUATION", "COMPLETED", "ABANDONED"})
    void aStateThatIsNotASectionHasNoSection(InterviewState state) {
        assertThatThrownBy(() -> TurnInputs.sectionOf(state)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theAnsweredQuestionCarriesItsTopicKeyDifficultyDepthAndKeyPointsInTheirOrder() {
        UUID interview = UUID.randomUUID();
        InterviewQuestion question = technical(interview, 4,
                new ExpectedKeyPoint("kp-b", "second by id but first in order", List.of()), new ExpectedKeyPoint("kp-a", "other", List.of()));
        CandidateAnswer answer = CandidateAnswer.submit(question.getId(), interview, "my answer", null, 10, NOW);

        AnsweredQuestion answered = TurnInputs.answered(question, answer, CONCURRENCY);

        assertThat(answered.answerId()).isEqualTo(answer.getId());
        assertThat(answered.questionId()).isEqualTo(question.getId());
        assertThat(answered.kind()).isEqualTo(QuestionKind.TECHNICAL);
        assertThat(answered.section()).isEqualTo(Section.QUESTIONING);
        assertThat(answered.topic()).isEqualTo("java/concurrency");
        assertThat(answered.difficulty()).isEqualTo(4);
        assertThat(answered.followUpDepth()).isZero();
        assertThat(answered.askedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(answered.expectedKeyPointIds()).containsExactly("kp-b", "kp-a");
    }

    @Test
    void aQuestionWithoutATopicHasNoTopicKeyAndAFollowUpKeepsItsDepth() {
        UUID interview = UUID.randomUUID();
        InterviewQuestion parent = technical(interview, 3);
        QuestionContent content = new QuestionContent("Why?", null, List.of(), "technical.v1", QuestionOrigin.GENERATED, null);
        InterviewQuestion followUp = InterviewQuestion.followUp(interview, 3, Section.QUESTIONING, null, 3, parent.getId(), 2, content, NOW);
        CandidateAnswer answer = CandidateAnswer.submit(followUp.getId(), interview, "because", null, null, NOW);

        AnsweredQuestion answered = TurnInputs.answered(followUp, answer, null);

        assertThat(answered.topic()).isNull();
        assertThat(answered.followUpDepth()).isEqualTo(2);
        assertThat(answered.kind()).isEqualTo(QuestionKind.FOLLOW_UP);
        assertThat(answered.expectedKeyPointIds()).isEmpty();
    }

    @Test
    void theInputCarriesTheStateTheDeadlineAndTheResumeSectionOfAFollowUpThread() {
        Interview interview = started(Fixtures.plan());
        interview.applyTransition(InterviewState.INTRODUCTION, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, NOW);
        InterviewQuestion question = technical(interview.getId(), 3);
        AnsweredQuestion answered = TurnInputs.answered(question, CandidateAnswer.submit(question.getId(), interview.getId(), "a", null, null, NOW), CONCURRENCY);
        Map<String, TopicRange> ranges = Map.of("java/concurrency", new TopicRange(2, 5));
        Instant later = NOW.plusSeconds(90);

        TurnInput running = TurnInputs.input(interview, answered, EvaluationOutcome.pending(), ranges, later, true, Set.of());

        assertThat(running.state()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(running.resumeSection()).isNull();
        assertThat(running.deadline()).isEqualTo(interview.getDeadlineAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(running.now()).isEqualTo(later);
        assertThat(running.plan()).isSameAs(interview.getPlan());
        assertThat(running.memory()).isSameAs(interview.getMemory());
        assertThat(running.topicRanges()).isEqualTo(ranges);
        assertThat(running.outcome().status()).isEqualTo(EvaluationOutcome.Status.PENDING);
        assertThat(running.endRequested()).as("only the finish endpoint ends an interview on request").isFalse();
        assertThat(running.repeatedAnswer()).isTrue();

        interview.applyTransition(InterviewState.QUESTIONING, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, NOW);
        TurnInput inThread = TurnInputs.input(interview, answered, EvaluationOutcome.pending(), ranges, later, false, Set.of());
        assertThat(inThread.state()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(inThread.resumeSection()).isEqualTo(Section.QUESTIONING);
        assertThat(inThread.repeatedAnswer()).isFalse();
    }

    @Test
    void anInterviewWithoutADeadlineCannotBeDecided() {
        Interview notStarted = Interview.create(UUID.randomUUID(), UUID.randomUUID(), Fixtures.plan(), NOW);
        AnsweredQuestion answered = new AnsweredQuestion(UUID.randomUUID(), UUID.randomUUID(), QuestionKind.INTRO, Section.INTRODUCTION, null, 1, 0, NOW, List.of());

        assertThatThrownBy(() -> TurnInputs.input(notStarted, answered, EvaluationOutcome.pending(), Map.of(), NOW, false, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no deadline");
    }
}
