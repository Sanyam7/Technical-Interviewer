package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techinterviewer.TestClock;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.service.CodingQuestions;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.design.DesignProblemBank;
import com.techinterviewer.design.DesignQuestions;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.interview.repository.QuestionGroundingRepository;
import com.techinterviewer.persistence.Fixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * What producing the next question does when another request changes the interview while the generator is working: the
 * database is replaced by doubles that lose the race on purpose.
 */
class QuestionProvisionerRaceTest {

    private static final UUID USER = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d61");
    private static final PendingStep STEP = PendingStep.nextTopic(Section.QUESTIONING, "java/concurrency", 3);
    private static final PendingStep OTHER_STEP = PendingStep.nextTopic(Section.QUESTIONING, "postgresql/indexing", 3);

    private final InterviewLoader loader = mock(InterviewLoader.class);
    private final InterviewWriter writer = mock(InterviewWriter.class);
    private final QuestionRequests requests = mock(QuestionRequests.class);
    private final QuestionGenerator generator = mock(QuestionGenerator.class);
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final ApiException conflict = new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "someone else changed the interview");
    private QuestionProvisioner provisioner;
    private Interview interview;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        provisioner = new QuestionProvisioner(
                new InterviewTransactions(manager), loader, writer, requests, generator, questions,
                mock(QuestionGroundingRepository.class), interviews, new TestClock(Fixtures.NOW),
                new CodingQuestions(ProblemBank.load()), new DesignQuestions(DesignProblemBank.load()));
        interview = Fixtures.interviewInQuestioning(USER, UUID.randomUUID());
        interview.replaceMemory(InterviewMemory.empty().withPendingStep(STEP), Fixtures.NOW);
        when(loader.load(interview.getId(), USER)).thenReturn(interview);
        when(writer.conflict(any(), any(), any())).thenReturn(conflict);
        QuestionRequest request = mock(QuestionRequest.class);
        when(request.targetDifficulty()).thenReturn(3);
        when(requests.draft(any(), any(), any())).thenReturn(new QuestionRequests.Draft(
                request, STEP, QuestionKind.TECHNICAL, 7L, 0, "technical.v1", List.of(), null));
        when(generator.generate(any())).thenReturn(new GeneratedQuestion(
                "Explain volatile.", List.of(), null, QuestionOrigin.GENERATED, 3, "java/concurrency", 4, List.of(), "p", "1", "m", null));
    }

    @Test
    void aQuestionForTheDecidedStepIsStoredAndBecomesTheCurrentQuestion() {
        assertThat(provisioner.provision(USER, interview.getId())).isTrue();

        assertThat(interview.getCurrentQuestionId()).isNotNull();
        assertThat(interview.getMemory().pendingStep()).isNull();
    }

    @Test
    void aQuestionForAStepThatWasReplacedWhileTheGeneratorWorkedIsDropped() {
        when(generator.generate(any())).thenAnswer(call -> {
            interview.replaceMemory(InterviewMemory.empty().withPendingStep(OTHER_STEP), Fixtures.NOW);
            return new GeneratedQuestion(
                    "Explain volatile.", List.of(), null, QuestionOrigin.GENERATED, 3, "java/concurrency", 4, List.of(), "p", "1", "m", null);
        });

        assertThat(provisioner.provision(USER, interview.getId())).isFalse();

        assertThat(interview.getCurrentQuestionId()).as("the question belongs to another step").isNull();
        assertThat(interview.getMemory().pendingStep()).isEqualTo(OTHER_STEP);
    }

    @Test
    void losingTheRaceToAnotherRequestThatStoredTheQuestionIsNotAnError() {
        when(interviews.saveAndFlush(any())).thenAnswer(call -> {
            interview.askQuestion(UUID.randomUUID(), Fixtures.NOW);
            throw new ObjectOptimisticLockingFailureException(Interview.class, interview.getId());
        });

        assertThat(provisioner.provision(USER, interview.getId())).isFalse();
    }

    @Test
    void losingTheRaceWhenNoQuestionWasStoredByAnyoneIsAConcurrentModification() {
        when(interviews.saveAndFlush(any())).thenAnswer(call -> {
            interview.clearCurrentQuestion(Fixtures.NOW); // the rolled-back transaction leaves no question behind
            throw new ObjectOptimisticLockingFailureException(Interview.class, interview.getId());
        });

        assertThatThrownBy(() -> provisioner.provision(USER, interview.getId())).isSameAs(conflict);
    }

    @Test
    void aSequenceNumberTakenByAnotherRequestWithoutAStoredQuestionIsAConflictToo() {
        when(questions.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_questions_interview_sequence"));

        assertThatThrownBy(() -> provisioner.provision(USER, interview.getId())).isSameAs(conflict);
    }
}
