package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.service.DeadlineService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The lazy deadline (D8): nothing runs in the background, a read that finds the deadline and the 30 second grace period
 * behind it ends the interview with TIME_UP, once, and before it builds its answer.
 */
class DeadlineApiIT extends InterviewApiTestBase {

    private static final Duration DURATION = Duration.ofMinutes(60);
    private static final Duration GRACE = Duration.ofSeconds(30);

    @Autowired
    private DeadlineService deadlines;

    private Interview open(UUID owner, InterviewState state) {
        return fixture.inState(owner, state, clock.instant());
    }

    private void goTo(Interview interview, Duration afterDeadline) {
        clock.set(interview.getDeadlineAt().plus(afterDeadline));
    }

    private List<InterviewStateTransition> log(UUID id) {
        return transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(id);
    }

    @Test
    void theInterviewIsLeftAloneUntilTheGraceAfterTheDeadlineIsOver() throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        UUID id = interview.getId();
        int rows = log(id).size();

        for (Duration late : List.of(Duration.ofMinutes(-10), Duration.ZERO, Duration.ofSeconds(1), GRACE.minusSeconds(1), GRACE)) {
            goTo(interview, late);
            getAs(ada, "/interviews/" + id)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("QUESTIONING"))
                    .andExpect(jsonPath("$.interviewComplete").value(false))
                    .andExpect(jsonPath("$.endReason").value((Object) null));
        }

        Interview stored = fixture.reload(id);
        assertThat(stored.getState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(stored.getEndedAt()).isNull();
        assertThat(stored.getVersion()).isEqualTo(interview.getVersion());
        assertThat(log(id)).hasSize(rows);
    }

    @Test
    void oneSecondAfterTheGraceTheReadEndsTheInterviewWithTimeUpAndShowsThat() throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        UUID id = interview.getId();
        goTo(interview, GRACE.plusSeconds(1));

        getAs(ada, "/interviews/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINAL_EVALUATION"))
                .andExpect(jsonPath("$.interviewComplete").value(true))
                .andExpect(jsonPath("$.endReason").value("TIME_UP"))
                .andExpect(jsonPath("$.currentQuestion").value((Object) null))
                .andExpect(jsonPath("$.awaitingNextQuestion").value(false))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(0))
                .andExpect(jsonPath("$.version").value(interview.getVersion() + 1));

        Interview stored = fixture.reload(id);
        assertThat(stored.getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(stored.getEndReason()).isEqualTo(EndReason.TIME_UP);
        assertThat(stored.getEndedAt()).isEqualTo(interview.getDeadlineAt());
        assertThat(stored.getCurrentQuestionId()).isNull();
        assertThat(stored.getDeadlineAt()).isEqualTo(interview.getStartedAt().plus(DURATION));
        InterviewStateTransition last = log(id).getLast();
        assertThat(last.getFromState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(last.getToState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(last.getEvent()).isEqualTo(InterviewEvent.TIME_UP);
        assertThat(last.getOccurredAt()).isEqualTo(interview.getDeadlineAt());
        assertThat(last.getDetails())
                .containsEntry("deadlineAt", interview.getDeadlineAt().toString())
                .containsEntry("detectedAt", clock.instant().toString());
    }

    @Test
    void theDeadlineIsAppliedExactlyOnceHoweverOftenTheInterviewIsReadAfterwards() throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        UUID id = interview.getId();
        int rows = log(id).size();
        goTo(interview, GRACE.plusSeconds(1));

        for (int i = 0; i < 3; i++) {
            getAs(ada, "/interviews/" + id).andExpect(jsonPath("$.state").value("FINAL_EVALUATION"));
            clock.advance(Duration.ofMinutes(5));
        }

        assertThat(log(id)).hasSize(rows + 1);
        assertThat(fixture.reload(id).getVersion()).isEqualTo(interview.getVersion() + 1);
        assertThat(fixture.reload(id).getEndedAt()).isEqualTo(interview.getDeadlineAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/state", "/questions", "/transcript"})
    void everyReadOfTheInterviewAppliesTheDeadlineBeforeItAnswers(String path) throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        goTo(interview, GRACE.plusSeconds(1));

        getAs(ada, "/interviews/" + interview.getId() + path).andExpect(status().isOk());

        assertThat(fixture.reload(interview.getId()).getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
    }

    @Test
    void theOpenQuestionIsGoneWhenTheDeadlineIsAppliedByAReadOfTheQuestions() throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        InterviewQuestion question = fixture.askTechnical(interview.getId(), "java", "concurrency", clock.instant());
        goTo(interview, GRACE.plusSeconds(1));

        getAs(ada, "/interviews/" + interview.getId() + "/questions/current").andExpect(status().isNotFound());
        getAs(ada, "/interviews/" + interview.getId() + "/questions/" + question.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answered").value(false));

        assertThat(fixture.reload(interview.getId()).getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(fixture.reload(interview.getId()).getCurrentQuestionId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INTRODUCTION", "QUESTIONING", "FOLLOW_UP", "CODING", "SYSTEM_DESIGN"})
    void everyStateThatAcceptsAnswersExpires(InterviewState state) throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, state);
        goTo(interview, GRACE.plusSeconds(1));

        getAs(ada, "/interviews/" + interview.getId() + "/state")
                .andExpect(jsonPath("$.state").value("FINAL_EVALUATION"))
                .andExpect(jsonPath("$.interviewComplete").value(true));

        assertThat(log(interview.getId()).getLast().getFromState()).isEqualTo(state);
        assertThat(fixture.reload(interview.getId()).getResumeState()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INIT", "FINAL_EVALUATION", "COMPLETED", "ABANDONED"})
    void anInterviewThatAsksNoQuestionsNeverExpires(InterviewState state) throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, state);
        clock.advance(Duration.ofDays(30));
        int rows = log(interview.getId()).size();

        getAs(ada, "/interviews/" + interview.getId()).andExpect(jsonPath("$.state").value(state.name()));

        Interview stored = fixture.reload(interview.getId());
        assertThat(stored.getState()).isEqualTo(state);
        assertThat(stored.getVersion()).isEqualTo(interview.getVersion());
        assertThat(log(interview.getId())).hasSize(rows);
    }

    @Test
    void aTimeUpThatWasAlreadyAppliedKeepsItsOwnEndReasonAndTime() throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        fixture.move(interview.getId(), InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED, clock.instant());
        goTo(interview, Duration.ofHours(2));

        getAs(ada, "/interviews/" + interview.getId()).andExpect(jsonPath("$.endReason").value("CANDIDATE_ENDED"));

        assertThat(fixture.reload(interview.getId()).getEndReason()).isEqualTo(EndReason.CANDIDATE_ENDED);
    }

    @Test
    void aQuestionThatWasDecidedButNotProducedIsForgottenWhenTimeIsUp() throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        fixture.setMemory(
                interview.getId(),
                InterviewMemory.empty().withPendingStep(PendingStep.nextTopic(Section.QUESTIONING, "java/collections", 3)),
                clock.instant());
        getAs(ada, "/interviews/" + interview.getId() + "/state").andExpect(jsonPath("$.awaitingNextQuestion").value(true));
        goTo(interview, GRACE.plusSeconds(1));

        getAs(ada, "/interviews/" + interview.getId() + "/state")
                .andExpect(jsonPath("$.awaitingNextQuestion").value(false))
                .andExpect(jsonPath("$.interviewComplete").value(true));

        assertThat(fixture.reload(interview.getId()).getMemory().pendingStep()).isNull();
    }

    @Test
    void theListEndsTheInterviewsOnThePageWhoseTimeIsUpAndOnlyTheirs() throws Exception {
        UUID ada = newUser();
        UUID bob = newUser();
        Interview late = open(ada, InterviewState.QUESTIONING);
        Interview foreign = open(bob, InterviewState.QUESTIONING);
        clock.advance(Duration.ofMinutes(1));
        Interview fresh = open(ada, InterviewState.QUESTIONING);
        goTo(late, GRACE.plusSeconds(1));
        assertThat(fresh.getDeadlineAt()).isAfter(clock.instant());

        getAs(ada, "/interviews?state=QUESTIONING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(fresh.getId().toString()));
        getAs(ada, "/interviews?state=FINAL_EVALUATION")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(late.getId().toString()))
                .andExpect(jsonPath("$.items[0].endReason").value("TIME_UP"))
                .andExpect(jsonPath("$.items[0].endedAt").value(late.getDeadlineAt().toString()));

        assertThat(fixture.reload(foreign.getId()).getState()).isEqualTo(InterviewState.QUESTIONING);
    }

    @Test
    void theServiceReportsWhetherItEndedTheInterviewAndIsIdempotent() {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.CODING);
        goTo(interview, GRACE);
        assertThat(deadlines.expireIfDue(interview.getId(), ada)).isFalse();

        goTo(interview, GRACE.plusSeconds(1));
        assertThat(deadlines.expireIfDue(interview.getId(), ada)).isTrue();
        assertThat(deadlines.expireIfDue(interview.getId(), ada)).isFalse();
        assertThat(fixture.reload(interview.getId()).getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
    }

    @Test
    void theServiceNeverEndsAnInterviewOfSomebodyElse() {
        UUID ada = newUser();
        UUID bob = newUser();
        Interview interview = open(ada, InterviewState.CODING);
        goTo(interview, GRACE.plusSeconds(1));

        assertThatThrownBy(() -> deadlines.expireIfDue(interview.getId(), bob))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Interview not found");

        assertThat(fixture.reload(interview.getId()).getState()).isEqualTo(InterviewState.CODING);
    }

    @Test
    void manySimultaneousReadsRecordExactlyOneTimeUp() throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        UUID id = interview.getId();
        int rows = log(id).size();
        goTo(interview, GRACE.plusSeconds(1));
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String path = i % 2 == 0 ? "/interviews/" + id : "/interviews/" + id + "/state";
                Callable<String> read = () -> {
                    go.await();
                    return getAs(ada, path).andReturn().getResponse().getStatus() + " "
                            + (path.endsWith("state") ? "state" : "session");
                };
                results.add(pool.submit(read));
            }
            go.countDown();
            for (Future<String> result : results) {
                assertThat(result.get()).startsWith("200 ");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(log(id)).hasSize(rows + 1);
        Interview stored = fixture.reload(id);
        assertThat(stored.getState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(stored.getVersion()).isEqualTo(interview.getVersion() + 1);
    }

    @Test
    void theClockOfTheServerAloneDecidesNotTheTimeOfTheRequest() throws Exception {
        UUID ada = newUser();
        Interview interview = open(ada, InterviewState.QUESTIONING);
        Instant justInTime = interview.getDeadlineAt().plus(GRACE);
        clock.set(justInTime);

        getAs(ada, "/interviews/" + interview.getId() + "/state").andExpect(jsonPath("$.state").value("QUESTIONING"));
        clock.set(justInTime.plusMillis(1));
        getAs(ada, "/interviews/" + interview.getId() + "/state").andExpect(jsonPath("$.state").value("FINAL_EVALUATION"));
    }
}
