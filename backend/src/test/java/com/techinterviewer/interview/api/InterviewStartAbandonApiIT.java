package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.engine.IntroductionPrompt;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.TargetRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class InterviewStartAbandonApiIT extends InterviewApiTestBase {

    private Interview stored(UUID id, UUID owner) {
        return interviews.findByIdAndCandidateId(id, owner).orElseThrow();
    }

    private List<InterviewStateTransition> log(UUID id) {
        return transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(id);
    }

    private long answerCount(UUID id) {
        return answers.countByInterviewId(id);
    }

    // ---- start -------------------------------------------------------------------------------------------------------

    @Test
    void startingSetsTheClockAsksTheIntroductionAndReturnsTheSession() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);
        clock.advance(Duration.ofMinutes(10));
        Instant startedAt = clock.instant();

        JsonNode session = body(postAs(ada, "/interviews/" + id + "/start")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.state").value("INTRODUCTION"))
                .andExpect(jsonPath("$.serverTime").value(startedAt.toString()))
                .andExpect(jsonPath("$.startedAt").value(startedAt.toString()))
                .andExpect(jsonPath("$.deadlineAt").value(startedAt.plus(Duration.ofMinutes(45)).toString()))
                .andExpect(jsonPath("$.endReason").value((Object) null))
                .andExpect(jsonPath("$.awaitingNextQuestion").value(false))
                .andExpect(jsonPath("$.interviewComplete").value(false))
                .andExpect(jsonPath("$.progress.section").value("INTRODUCTION"))
                .andExpect(jsonPath("$.progress.sectionIndex").value(0))
                .andExpect(jsonPath("$.progress.sectionCount").value(2))
                .andExpect(jsonPath("$.progress.questionsAnswered").value(0))
                .andExpect(jsonPath("$.progress.plannedQuestionsInSection").value(1))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(2700))
                .andExpect(jsonPath("$.configuration.interviewType").value("TECHNICAL"))
                .andExpect(jsonPath("$.currentQuestion.sequenceNo").value(1))
                .andExpect(jsonPath("$.currentQuestion.kind").value("INTRO"))
                .andExpect(jsonPath("$.currentQuestion.isFollowUp").value(false))
                .andExpect(jsonPath("$.currentQuestion.section").value("INTRODUCTION"))
                .andExpect(jsonPath("$.currentQuestion.topic").value((Object) null))
                .andExpect(jsonPath("$.currentQuestion.answerFormat").value("TEXT"))
                .andExpect(jsonPath("$.currentQuestion.details").value((Object) null))
                .andExpect(jsonPath("$.currentQuestion.answered").value(false)));

        String expectedPrompt = IntroductionPrompt.render(TargetRole.BACKEND, ExperienceLevel.MID, List.of("Java", "Spring Boot"));
        assertThat(session.get("currentQuestion").get("prompt").asString()).isEqualTo(expectedPrompt);
        assertThat(session.get("currentQuestion").get("suggestedMinutes").asInt()).isPositive();
    }

    @Test
    void startingWritesExactlyTheInterviewTheTransitionAndTheIntroductionQuestion() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);
        clock.advance(Duration.ofMinutes(10));
        Instant startedAt = clock.instant();

        JsonNode session = body(postAs(ada, "/interviews/" + id + "/start").andExpect(status().isOk()));

        Interview interview = stored(id, ada);
        assertThat(interview.getState()).isEqualTo(InterviewState.INTRODUCTION);
        assertThat(interview.getStartedAt()).isEqualTo(startedAt);
        assertThat(interview.getDeadlineAt()).isEqualTo(startedAt.plus(Duration.ofMinutes(45)));
        assertThat(interview.getEndedAt()).isNull();
        assertThat(interview.getEndReason()).isNull();
        assertThat(interview.getResumeState()).isNull();
        assertThat(interview.getVersion()).isEqualTo(session.get("version").asLong()).isPositive();
        assertThat(interview.getMemory().sectionProgress()).containsOnlyKeys(Section.INTRODUCTION);
        assertThat(interview.getMemory().sectionProgress().get(Section.INTRODUCTION)).isEqualTo(new SectionProgress(0, startedAt));
        assertThat(interview.getMemory().pendingStep()).isNull();

        List<InterviewQuestion> asked = questions.findByInterviewIdOrderBySequenceNoAsc(id);
        assertThat(asked).hasSize(1);
        InterviewQuestion intro = asked.getFirst();
        assertThat(interview.getCurrentQuestionId()).isEqualTo(intro.getId());
        assertThat(session.get("currentQuestion").get("id").asString()).isEqualTo(intro.getId().toString());
        assertThat(intro.getSequenceNo()).isEqualTo(1);
        assertThat(intro.getKind()).isEqualTo(QuestionKind.INTRO);
        assertThat(intro.getSection()).isEqualTo(Section.INTRODUCTION);
        assertThat(intro.getOrigin()).isEqualTo(QuestionOrigin.STATIC);
        assertThat(intro.getRubricId()).isEqualTo(RubricRegistry.forEvaluator(Evaluator.INTRO).id()).isEqualTo("intro.v1");
        assertThat(intro.getParentQuestionId()).isNull();
        assertThat(intro.getTopicId()).isNull();
        assertThat(intro.getFollowUpDepth()).isZero();
        assertThat(intro.getDifficulty()).isEqualTo(interview.getPlan().difficulty().start());
        assertThat(intro.getExpectedKeyPoints()).isEmpty();
        assertThat(intro.getDetails()).isNull();
        assertThat(intro.getGenerationLlmCallId()).isNull();
        assertThat(intro.getAskedAt()).isEqualTo(startedAt);
        assertThat(answerCount(id)).isZero();

        List<InterviewStateTransition> log = log(id);
        assertThat(log).hasSize(2);
        assertThat(log.get(0).getFromState()).isNull();
        assertThat(log.get(0).getEvent()).isEqualTo(InterviewEvent.CREATE);
        assertThat(log.get(1).getFromState()).isEqualTo(InterviewState.INIT);
        assertThat(log.get(1).getToState()).isEqualTo(InterviewState.INTRODUCTION);
        assertThat(log.get(1).getEvent()).isEqualTo(InterviewEvent.START);
        assertThat(log.get(1).getOccurredAt()).isEqualTo(startedAt);
    }

    @Test
    void theDeadlineCountsFromTheStartNotFromTheCreationAndFromTheConfiguredDuration() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada, FULL_LOOP_CONFIGURATION);
        clock.advance(Duration.ofHours(3));

        postAs(ada, "/interviews/" + id + "/start")
                .andExpect(jsonPath("$.startedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.deadlineAt").value(clock.instant().plus(Duration.ofMinutes(60)).toString()))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(3600))
                .andExpect(jsonPath("$.progress.sectionCount").value(4));
    }

    @Test
    void theIntroductionNamesTheSkillsOfTheConfiguration() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada, with(TECHNICAL_CONFIGURATION, "skills", List.of("postgresql", "react")));

        postAs(ada, "/interviews/" + id + "/start")
                .andExpect(jsonPath("$.currentQuestion.prompt", containsString("PostgreSQL and React")));
    }

    @Test
    void startingTwiceIsA409WithTheCurrentStateAndAddsNothing() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        Interview before = stored(id, ada);

        postAs(ada, "/interviews/" + id + "/start")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentState").value("INTRODUCTION"));

        Interview after = stored(id, ada);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getStartedAt()).isEqualTo(before.getStartedAt());
        assertThat(questions.findByInterviewIdOrderBySequenceNoAsc(id)).hasSize(1);
        assertThat(log(id)).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INIT"}, mode = EnumSource.Mode.EXCLUDE)
    void aStartIsRefusedInEveryStateButInit(InterviewState state) throws Exception {
        UUID ada = newUser();
        Interview interview = fixture.inState(ada, state, clock.instant());
        long logSize = log(interview.getId()).size();
        long questionCount = questions.findByInterviewIdOrderBySequenceNoAsc(interview.getId()).size();

        postAs(ada, "/interviews/" + interview.getId() + "/start")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentState").value(state.name()));

        assertThat(stored(interview.getId(), ada).getState()).isEqualTo(state);
        assertThat(stored(interview.getId(), ada).getVersion()).isEqualTo(interview.getVersion());
        assertThat(log(interview.getId())).hasSize((int) logSize);
        assertThat(questions.findByInterviewIdOrderBySequenceNoAsc(interview.getId())).hasSize((int) questionCount);
    }

    @Test
    void startingAnUnknownOrForeignInterviewIsA404AndChangesNothing() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();
        UUID gracesInterview = createInterview(grace);

        postAs(ada, "/interviews/" + gracesInterview + "/start").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        postAs(ada, "/interviews/" + UUID.randomUUID() + "/start").andExpect(status().isNotFound());

        assertThat(stored(gracesInterview, grace).getState()).isEqualTo(InterviewState.INIT);
        assertThat(questions.findByInterviewIdOrderBySequenceNoAsc(gracesInterview)).isEmpty();
    }

    @Test
    void startingHasNoBodyAndIgnoresOne() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);

        postAs(ada, "/interviews/" + id + "/start", "{\"state\":\"COMPLETED\"}").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("INTRODUCTION"));
    }

    // ---- abandon ---------------------------------------------------------------------------------------------------

    @Test
    void anInterviewThatWasNotStartedCanBeAbandoned() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);
        clock.advance(Duration.ofMinutes(3));

        postAs(ada, "/interviews/" + id + "/abandon")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ABANDONED"))
                .andExpect(jsonPath("$.endReason").value("ABANDONED"))
                .andExpect(jsonPath("$.interviewComplete").value(false))
                .andExpect(jsonPath("$.currentQuestion").value((Object) null))
                .andExpect(jsonPath("$.startedAt").value((Object) null))
                .andExpect(jsonPath("$.deadlineAt").value((Object) null))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(0));

        Interview interview = stored(id, ada);
        assertThat(interview.getState()).isEqualTo(InterviewState.ABANDONED);
        assertThat(interview.getEndReason()).isEqualTo(EndReason.ABANDONED);
        assertThat(interview.getEndedAt()).isEqualTo(clock.instant());
        assertThat(interview.getStartedAt()).isNull();
        List<InterviewStateTransition> log = log(id);
        assertThat(log).hasSize(2);
        assertThat(log.get(1).getFromState()).isEqualTo(InterviewState.INIT);
        assertThat(log.get(1).getToState()).isEqualTo(InterviewState.ABANDONED);
        assertThat(log.get(1).getEvent()).isEqualTo(InterviewEvent.ABANDON);
        assertThat(log.get(1).getOccurredAt()).isEqualTo(clock.instant());
        assertThat(questions.findByInterviewIdOrderBySequenceNoAsc(id)).isEmpty();
    }

    @Test
    void anAbandonedInterviewCanBeReadButNeitherStartedNorAbandonedAgain() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);
        postAs(ada, "/interviews/" + id + "/abandon").andExpect(status().isOk());

        getAs(ada, "/interviews/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ABANDONED"));
        postAs(ada, "/interviews/" + id + "/start").andExpect(status().isConflict()).andExpect(jsonPath("$.currentState").value("ABANDONED"));
        postAs(ada, "/interviews/" + id + "/abandon").andExpect(status().isConflict()).andExpect(jsonPath("$.currentState").value("ABANDONED"));
        assertThat(log(id)).hasSize(2);
        getAs(ada, "/interviews?state=ABANDONED").andExpect(jsonPath("$.items", hasSize(1)));
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INIT", "ABANDONED"}, mode = EnumSource.Mode.EXCLUDE)
    void aStartedInterviewCannotBeAbandoned(InterviewState state) throws Exception {
        UUID ada = newUser();
        Interview interview = fixture.inState(ada, state, clock.instant());

        postAs(ada, "/interviews/" + interview.getId() + "/abandon")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentState").value(state.name()));

        Interview after = stored(interview.getId(), ada);
        assertThat(after.getState()).isEqualTo(state);
        assertThat(after.getVersion()).isEqualTo(interview.getVersion());
        assertThat(after.getEndReason()).isEqualTo(interview.getEndReason());
    }

    @Test
    void abandoningAnUnknownOrForeignInterviewIsA404AndChangesNothing() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();
        UUID gracesInterview = createInterview(grace);

        postAs(ada, "/interviews/" + gracesInterview + "/abandon").andExpect(status().isNotFound());
        postAs(ada, "/interviews/" + UUID.randomUUID() + "/abandon").andExpect(status().isNotFound());

        assertThat(stored(gracesInterview, grace).getState()).isEqualTo(InterviewState.INIT);
        assertThat(log(gracesInterview)).hasSize(1);
    }
}
