package com.techinterviewer.interview.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class InterviewRepositoryIT extends PersistenceTestBase {

    private static final Instant T0 = Fixtures.NOW;

    private Interview load(UUID id) {
        return inTransaction(() -> interviews.findById(id).orElseThrow());
    }

    private InterviewConfiguration configurationOf(UUID user) {
        InterviewConfiguration configuration = Fixtures.technicalConfiguration(user, Set.of());
        inTransaction(() -> configurations.saveAndFlush(configuration));
        return configuration;
    }

    private Interview saveInterviewCreatedAt(UUID user, UUID configuration, Instant createdAt) {
        Interview interview = Interview.create(user, configuration, Fixtures.plan(), createdAt);
        inTransaction(() -> interviews.saveAndFlush(interview));
        return interview;
    }

    // ---- mapping --------------------------------------------------------------------------------------------------

    @Test
    void aNewInterviewIsStoredWithEveryFieldAndStartsAtVersionZero() {
        UUID user = newUser();
        InterviewConfiguration configuration = configurationOf(user);
        Interview original = Fixtures.interview(user, configuration.getId());
        inTransaction(() -> interviews.saveAndFlush(original));

        Interview loaded = load(original.getId());

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getCandidateId()).isEqualTo(user);
        assertThat(loaded.getConfigurationId()).isEqualTo(configuration.getId());
        assertThat(loaded.getState()).isEqualTo(InterviewState.INIT);
        assertThat(loaded.getPlan()).isEqualTo(Fixtures.plan());
        assertThat(loaded.getMemory()).isEqualTo(InterviewMemory.empty());
        assertThat(loaded.getCreatedAt()).isEqualTo(T0);
        assertThat(loaded.getUpdatedAt()).isEqualTo(T0);
        assertThat(loaded.getStartedAt()).isNull();
        assertThat(loaded.getVersion()).isZero();
        assertThat(jdbc.queryForObject("select state from interviews where id = ?", String.class, original.getId())).isEqualTo("INIT");
    }

    @Test
    void aRunningInterviewKeepsItsMemoryDeadlineAndCurrentQuestionAcrossAWriteAndARead() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);

        Interview running = inTransaction(() -> {
            Interview interview = interviews.findById(scenario.interviewId()).orElseThrow();
            interview.start(T0, 60);
            interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
            interview.applyTransition(InterviewState.INTRODUCTION, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, T0);
            interview.askQuestion(question.getId(), T0.plusSeconds(5));
            interview.replaceMemory(Fixtures.memory(), T0.plusSeconds(6));
            return interview;
        });

        Interview loaded = load(scenario.interviewId());

        assertThat(loaded).isNotSameAs(running).usingRecursiveComparison().isEqualTo(running);
        assertThat(loaded.getState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(loaded.getCurrentQuestionId()).isEqualTo(question.getId());
        assertThat(loaded.getStartedAt()).isEqualTo(T0);
        assertThat(loaded.getDeadlineAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(loaded.getMemory()).isEqualTo(Fixtures.memory());
        assertThat(loaded.getMemory().pendingStep()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(T0.plusSeconds(6));
        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(loaded.getEndedAt()).isNull();
        assertThat(loaded.getEndReason()).isNull();
    }

    @Test
    void theSectionToResumeAfterAFollowUpIsStoredOnlyWhileInTheFollowUp() {
        Scenario scenario = newScenario();
        inTransaction(() -> {
            Interview interview = interviews.findById(scenario.interviewId()).orElseThrow();
            interview.start(T0, 30);
            interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
            interview.applyTransition(InterviewState.INTRODUCTION, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, T0);
            interview.applyTransition(InterviewState.QUESTIONING, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, T0);
        });

        Interview inFollowUp = load(scenario.interviewId());

        assertThat(inFollowUp.getState()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(inFollowUp.getResumeState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(jdbc.queryForObject("select resume_state from interviews where id = ?", String.class, scenario.interviewId()))
                .isEqualTo("QUESTIONING");

        inTransaction(() -> interviews.findById(scenario.interviewId()).orElseThrow()
                .applyTransition(InterviewState.FOLLOW_UP, InterviewState.QUESTIONING, InterviewEvent.FOLLOW_UP_DONE, T0));

        assertThat(load(scenario.interviewId()).getResumeState()).isNull();
        assertThat(jdbc.queryForObject("select resume_state from interviews where id = ?", String.class, scenario.interviewId())).isNull();
    }

    @Test
    void anInterviewThatEndedStoresWhenAndWhy() {
        Scenario scenario = newScenario();
        Instant end = T0.plusSeconds(2000);
        inTransaction(() -> {
            Interview interview = interviews.findById(scenario.interviewId()).orElseThrow();
            interview.start(T0, 30);
            interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
            interview.applyTransition(InterviewState.INTRODUCTION, InterviewState.FINAL_EVALUATION, InterviewEvent.TIME_UP, end);
        });

        Interview loaded = load(scenario.interviewId());

        assertThat(loaded.getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(loaded.getEndedAt()).isEqualTo(end);
        assertThat(loaded.getEndReason()).isEqualTo(EndReason.TIME_UP);
        assertThat(jdbc.queryForObject("select end_reason from interviews where id = ?", String.class, scenario.interviewId()))
                .isEqualTo("TIME_UP");
    }

    @Test
    void anAbandonedInterviewStoresTheAbandonedEndReason() {
        Scenario scenario = newScenario();
        inTransaction(() -> interviews.findById(scenario.interviewId()).orElseThrow()
                .applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, T0.plusSeconds(9)));

        Interview loaded = load(scenario.interviewId());

        assertThat(loaded.getState()).isEqualTo(InterviewState.ABANDONED);
        assertThat(loaded.getEndReason()).isEqualTo(EndReason.ABANDONED);
        assertThat(loaded.getEndedAt()).isEqualTo(T0.plusSeconds(9));
        assertThat(loaded.isTerminal()).isTrue();
    }

    @Test
    void thePlanIsStoredAsOneJsonDocumentAndItsColumnIsNeverUpdated() {
        Scenario scenario = newScenario();
        assertThat(jdbc.queryForObject(
                "select plan ->> 'version' from interviews where id = ?", String.class, scenario.interviewId()))
                .isEqualTo(String.valueOf(Fixtures.plan().version()));
        assertThat(jdbc.queryForObject(
                "select jsonb_array_length(plan -> 'sections') from interviews where id = ?", Integer.class, scenario.interviewId()))
                .isEqualTo(Fixtures.plan().sections().size());
        InterviewPlan tampered = new InterviewPlan(
                Fixtures.plan().version(), Fixtures.plan().sections(), Fixtures.plan().difficulty(), 0);

        inTransaction(() -> {
            Interview interview = interviews.findById(scenario.interviewId()).orElseThrow();
            ReflectionTestUtils.setField(interview, "plan", tampered);
            interview.replaceMemory(Fixtures.memory(), T0.plusSeconds(1));
        });

        assertThat(load(scenario.interviewId()).getMemory()).as("the update did happen").isEqualTo(Fixtures.memory());
        assertThat(load(scenario.interviewId()).getPlan()).isEqualTo(Fixtures.plan());
        assertThat(jdbc.queryForObject(
                "select plan ->> 'maxFollowUpDepth' from interviews where id = ?", String.class, scenario.interviewId()))
                .isEqualTo(String.valueOf(Fixtures.plan().maxFollowUpDepth()));
    }

    // ---- ownership-scoped finders -------------------------------------------------------------------------------

    @Test
    void anInterviewIsFoundOnlyForItsCandidate() {
        Scenario scenario = newScenario();
        UUID stranger = newUser();

        assertThat(interviews.findByIdAndCandidateId(scenario.interviewId(), scenario.userId())).isPresent()
                .get().extracting(Interview::getId).isEqualTo(scenario.interviewId());
        assertThat(interviews.findByIdAndCandidateId(scenario.interviewId(), stranger)).isEmpty();
        assertThat(interviews.findByIdAndCandidateId(UuidV7.generate(), scenario.userId())).isEmpty();
    }

    @Test
    void theListOfACandidateHoldsOnlyTheirInterviewsNewestFirstAndIsPaged() {
        UUID user = newUser();
        UUID stranger = newUser();
        UUID configuration = configurationOf(user).getId();
        UUID strangerConfiguration = configurationOf(stranger).getId();
        Interview oldest = saveInterviewCreatedAt(user, configuration, T0);
        Interview middle = saveInterviewCreatedAt(user, configuration, T0.plusSeconds(10));
        Interview newest = saveInterviewCreatedAt(user, configuration, T0.plusSeconds(20));
        saveInterviewCreatedAt(stranger, strangerConfiguration, T0.plusSeconds(30));

        Page<Interview> first = interviews.findPageByCandidateId(user, PageRequest.of(0, 2));
        Page<Interview> second = interviews.findPageByCandidateId(user, PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(Interview::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(second.getContent()).extracting(Interview::getId).containsExactly(oldest.getId());
        assertThat(interviews.findPageByCandidateId(UuidV7.generate(), PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    void interviewsCreatedInTheSameInstantAreOrderedByTheirTimeOrderedId() {
        UUID user = newUser();
        UUID configuration = configurationOf(user).getId();
        Interview first = saveInterviewCreatedAt(user, configuration, T0);
        Interview second = saveInterviewCreatedAt(user, configuration, T0);

        assertThat(interviews.findPageByCandidateId(user, PageRequest.of(0, 10)).getContent())
                .extracting(Interview::getId).containsExactly(second.getId(), first.getId());
    }

    @Test
    void interviewsOfTheSameInstantAndStateAreOrderedByTheirTimeOrderedIdInTheStateFilterToo() {
        UUID user = newUser();
        UUID configuration = configurationOf(user).getId();
        Interview first = saveInterviewCreatedAt(user, configuration, T0);
        Interview second = saveInterviewCreatedAt(user, configuration, T0);

        assertThat(interviews.findPageByCandidateIdAndState(user, InterviewState.INIT, PageRequest.of(0, 10)).getContent())
                .extracting(Interview::getId).containsExactly(second.getId(), first.getId());
    }

    @Test
    void theStateFilterKeepsOnlyInterviewsInThatStateOfThatCandidate() {
        UUID user = newUser();
        UUID stranger = newUser();
        UUID configuration = configurationOf(user).getId();
        UUID strangerConfiguration = configurationOf(stranger).getId();
        Interview init = saveInterviewCreatedAt(user, configuration, T0);
        Interview abandoned = saveInterviewCreatedAt(user, configuration, T0.plusSeconds(5));
        saveInterviewCreatedAt(stranger, strangerConfiguration, T0);
        Interview strangersAbandoned = saveInterviewCreatedAt(stranger, strangerConfiguration, T0.plusSeconds(6));
        inTransaction(() -> {
            interviews.findById(abandoned.getId()).orElseThrow()
                    .applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, T0.plusSeconds(7));
            interviews.findById(strangersAbandoned.getId()).orElseThrow()
                    .applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, T0.plusSeconds(7));
        });

        assertThat(interviews.findPageByCandidateIdAndState(user, InterviewState.ABANDONED, PageRequest.of(0, 10)).getContent())
                .extracting(Interview::getId).containsExactly(abandoned.getId());
        assertThat(interviews.findPageByCandidateIdAndState(user, InterviewState.INIT, PageRequest.of(0, 10)).getContent())
                .extracting(Interview::getId).containsExactly(init.getId());
        assertThat(interviews.findPageByCandidateIdAndState(user, InterviewState.QUESTIONING, PageRequest.of(0, 10)).getContent())
                .isEmpty();
    }

    @Test
    void theDailyCountIncludesTheBoundaryInstantAndNeverCountsAnotherCandidate() {
        UUID user = newUser();
        UUID stranger = newUser();
        UUID configuration = configurationOf(user).getId();
        UUID strangerConfiguration = configurationOf(stranger).getId();
        saveInterviewCreatedAt(user, configuration, T0.minusSeconds(1));
        saveInterviewCreatedAt(user, configuration, T0);
        saveInterviewCreatedAt(user, configuration, T0.plusSeconds(60));
        saveInterviewCreatedAt(stranger, strangerConfiguration, T0.plusSeconds(60));

        assertThat(interviews.countByCandidateIdAndCreatedAtGreaterThanEqual(user, T0)).isEqualTo(2);
        assertThat(interviews.countByCandidateIdAndCreatedAtGreaterThanEqual(user, T0.plusSeconds(1))).isEqualTo(1);
        assertThat(interviews.countByCandidateIdAndCreatedAtGreaterThanEqual(user, T0.plusSeconds(61))).isZero();
        assertThat(interviews.countByCandidateIdAndCreatedAtGreaterThanEqual(stranger, T0)).isEqualTo(1);
    }

    @Test
    void aConfigurationInUseIsReportedAsUsed() {
        Scenario scenario = newScenario();
        InterviewConfiguration unused = configurationOf(scenario.userId());

        assertThat(interviews.existsByConfigurationId(scenario.configuration().getId())).isTrue();
        assertThat(interviews.existsByConfigurationId(unused.getId())).isFalse();
    }

    // ---- constraints ----------------------------------------------------------------------------------------------

    @Test
    void anInterviewCannotPointAtAConfigurationOrCandidateThatDoesNotExist() {
        UUID user = newUser();
        Interview withoutConfiguration = Fixtures.interview(user, UuidV7.generate());
        assertThatThrownBy(() -> inTransaction(() -> interviews.saveAndFlush(withoutConfiguration)))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID configuration = configurationOf(user).getId();
        Interview withoutCandidate = Fixtures.interview(UuidV7.generate(), configuration);
        assertThatThrownBy(() -> inTransaction(() -> interviews.saveAndFlush(withoutCandidate)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theCurrentQuestionIsClearedWhenItsQuestionIsDeleted() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        inTransaction(() -> {
            Interview interview = interviews.findById(scenario.interviewId()).orElseThrow();
            interview.start(T0, 60);
            interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
            interview.askQuestion(question.getId(), T0);
        });
        assertThat(load(scenario.interviewId()).getCurrentQuestionId()).isEqualTo(question.getId());

        jdbc.update("delete from questions where id = ?", question.getId());

        assertThat(load(scenario.interviewId()).getCurrentQuestionId())
                .as("fk_interviews_current_question is ON DELETE SET NULL").isNull();
    }

    @Test
    void theCurrentQuestionMustBeARealQuestion() {
        Scenario scenario = newScenario();

        assertThatThrownBy(() -> inTransaction(() -> {
            Interview interview = interviews.findById(scenario.interviewId()).orElseThrow();
            interview.start(T0, 60);
            interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, T0);
            interview.askQuestion(UuidV7.generate(), T0);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- optimistic locking ---------------------------------------------------------------------------------------

    @Test
    void everyUpdateRaisesTheVersionByOne() {
        Scenario scenario = newScenario();

        inTransaction(() -> interviews.findById(scenario.interviewId()).orElseThrow().replaceMemory(Fixtures.memory(), T0.plusSeconds(1)));
        inTransaction(() -> interviews.findById(scenario.interviewId()).orElseThrow()
                .replaceMemory(Fixtures.memory().withPendingStep(null), T0.plusSeconds(2)));

        assertThat(load(scenario.interviewId()).getVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select version from interviews where id = ?", Long.class, scenario.interviewId())).isEqualTo(2L);
    }

    @Test
    void theSecondOfTwoPersistenceContextsUpdatingTheSameInterviewFails() {
        Scenario scenario = newScenario();
        InterviewMemory winner = Fixtures.memory();
        InterviewMemory loser = Fixtures.memory().withPendingStep(null);

        assertThatThrownBy(() -> inTransaction(() -> {
            Interview stale = interviews.findById(scenario.interviewId()).orElseThrow();
            inNewTransaction(() -> {
                Interview other = interviews.findById(scenario.interviewId()).orElseThrow();
                other.replaceMemory(winner, T0.plusSeconds(1));
                return interviews.saveAndFlush(other);
            });
            stale.replaceMemory(loser, T0.plusSeconds(2));
            interviews.saveAndFlush(stale);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Interview stored = load(scenario.interviewId());
        assertThat(stored.getMemory()).as("the first writer's data stands").isEqualTo(winner);
        assertThat(stored.getVersion()).isEqualTo(1);
    }

    @Test
    void aStepThatOnlyInsertsRowsStillTakesPartInLockingWhenItBumpsTheVersion() {
        Scenario scenario = newScenario();

        inTransaction(() -> interviews.findAndBumpVersionByIdAndCandidateId(scenario.interviewId(), scenario.userId()).orElseThrow());

        assertThat(load(scenario.interviewId()).getVersion()).as("nothing changed, the version moved").isEqualTo(1);
        assertThat(inTransaction(() -> interviews.findAndBumpVersionByIdAndCandidateId(scenario.interviewId(), newUser()))).isEmpty();
    }

    @Test
    void twoStepsThatBumpTheSameVersionCannotBothCommit() {
        Scenario scenario = newScenario();

        assertThatThrownBy(() -> inTransaction(() -> {
            interviews.findAndBumpVersionByIdAndCandidateId(scenario.interviewId(), scenario.userId()).orElseThrow();
            inNewTransaction(() -> interviews.findAndBumpVersionByIdAndCandidateId(scenario.interviewId(), scenario.userId()).orElseThrow());
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(load(scenario.interviewId()).getVersion()).isEqualTo(1);
    }

    @Test
    void anInterviewReadAndNeverChangedKeepsItsVersion() {
        Scenario scenario = newScenario();

        inTransaction(() -> interviews.findById(scenario.interviewId()).orElseThrow());

        assertThat(load(scenario.interviewId()).getVersion()).isZero();
    }
}
