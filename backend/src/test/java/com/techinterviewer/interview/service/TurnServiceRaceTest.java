package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techinterviewer.TestClock;
import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.evaluation.service.EvaluationService;
import com.techinterviewer.interview.api.dto.AnswerRequest;
import com.techinterviewer.interview.api.dto.AnswerResponse;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.engine.TopicRange;
import com.techinterviewer.interview.port.AnswerEvaluator;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * What a turn does when a concurrent request wins a race on the interview or on the answer row, with the database
 * replaced by doubles that lose the race on purpose (a real race cannot be made to happen at an exact line).
 */
class TurnServiceRaceTest {

    private static final Instant NOW = Fixtures.NOW;
    private static final UUID USER = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d41");

    private final InterviewLoader loader = mock(InterviewLoader.class);
    private final InterviewWriter writer = mock(InterviewWriter.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final CandidateAnswerRepository answers = mock(CandidateAnswerRepository.class);
    private final SessionAssembler assembler = mock(SessionAssembler.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private final AnswerResponse response = mock(AnswerResponse.class);
    private final ApiException conflict = new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "someone else changed the interview");
    private final AtomicReference<CandidateAnswer> stored = new AtomicReference<>();

    private TurnService service;
    private Interview interview;
    private InterviewQuestion intro;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new TurnService(
                new TestClock(NOW),
                new InterviewTransactions(manager),
                loader,
                writer,
                mock(DeadlineService.class),
                mock(TransitionApplier.class),
                mock(EvaluationService.class),
                mock(AnswerEvaluator.class),
                mock(QuestionProvisioner.class),
                assembler,
                catalog,
                interviews,
                answers,
                mock(EvaluationRepository.class),
                mock(jakarta.persistence.EntityManager.class));
        interview = Fixtures.interview(USER, UUID.randomUUID());
        interview.start(NOW, 60);
        interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, NOW);
        intro = Fixtures.introQuestion(interview.getId());
        interview.askQuestion(intro.getId(), NOW);
        InterviewConfiguration configuration = Fixtures.configuration(USER, Set.of(1L));
        when(loader.load(interview.getId(), USER)).thenReturn(interview);
        when(loader.loadQuestion(interview, intro.getId())).thenReturn(intro);
        when(loader.configurationOf(interview)).thenReturn(configuration);
        when(writer.conflict(any(), any(), any())).thenReturn(conflict);
        when(assembler.answer(interview)).thenReturn(response);
        when(answers.saveAndFlush(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return stored.get();
        });
        when(answers.findByIdAndInterviewId(any(), any())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(catalog.topicRanges(any())).thenAnswer(call -> {
            Map<String, TopicRange> ranges = new LinkedHashMap<>();
            for (Object key : (Iterable<?>) call.getArgument(0)) {
                ranges.put((String) key, new TopicRange(1, 5));
            }
            return ranges;
        });
    }

    private AnswerResponse answerIntro() {
        return service.answer(USER, interview.getId(), new AnswerRequest(intro.getId(), "I build backends.", null, null));
    }

    @Test
    void aTurnThatNobodyDisturbsReturnsTheStateAfterTheTurn() {
        assertThat(answerIntro()).isSameAs(response);
        assertThat(stored.get().getContent()).isEqualTo("I build backends.");
    }

    @Test
    void anAnswerThatLosesTheRaceOnTheInterviewAndWasNotStoredByAnyoneIsAConcurrentModification() {
        when(loader.load(interview.getId(), USER)).thenThrow(new ObjectOptimisticLockingFailureException(Interview.class, interview.getId()));

        assertThatThrownBy(this::answerIntro).isSameAs(conflict);
    }

    @Test
    void aFailureOfTheAnswerRowThatIsNoRaceOnTheAnswerIsNotDisguised() {
        DataIntegrityViolationException broken = new DataIntegrityViolationException("answers_time_taken_check");
        when(answers.saveAndFlush(any())).thenThrow(broken);

        assertThatThrownBy(this::answerIntro).isSameAs(broken);
    }

    @Test
    void anAnswerThatLosesTheRaceToTheSameAnswerIsAnIdempotentRetry() {
        when(answers.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_answers_question"));
        when(answers.existsByQuestionIdAndInterviewId(intro.getId(), interview.getId())).thenReturn(false, true);

        assertThat(answerIntro()).isSameAs(response);
    }

    @Test
    void aTurnThatLosesTheRaceWhileItDecidesIsAConcurrentModificationWithTheStateOfTheWinner() {
        when(interviews.saveAndFlush(any())).thenThrow(new ObjectOptimisticLockingFailureException(Interview.class, interview.getId()));

        assertThatThrownBy(this::answerIntro).isSameAs(conflict);
    }

    @Test
    void aTurnWhoseDecisionBreaksAUniqueRuleIsAlsoAConflictNotAServerError() {
        when(interviews.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_state_transitions"));

        assertThatThrownBy(this::answerIntro).isSameAs(conflict);
    }

    @Test
    void theServiceItselfRefusesAnAnswerOfOnlyWhitespaceEvenIfTheRequestRulesWereBypassed() {
        assertThatThrownBy(() -> service.answer(USER, interview.getId(), new AnswerRequest(intro.getId(), "  \n\t ", null, null)))
                .isInstanceOf(com.techinterviewer.common.error.FieldValidationException.class);
        assertThat(stored.get()).as("nothing was stored").isNull();
    }
}
