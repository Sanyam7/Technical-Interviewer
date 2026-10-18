package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/** The read side of the interview API: session, state, questions, transcript and list, on rows the API cannot produce yet. */
class InterviewQueryApiIT extends InterviewApiTestBase {

    private static final String SECRET_RATIONALE = "secret-rationale";

    private InterviewQuestion introductionOf(UUID interviewId) {
        return questions.findByInterviewIdOrderBySequenceNoAsc(interviewId).getFirst();
    }

    /** A FULL_LOOP interview in QUESTIONING: introduction answered, one main question answered, a follow-up and a third question open. */
    private record Midway(UUID owner, UUID id, InterviewQuestion intro, InterviewQuestion first, InterviewQuestion followUp, InterviewQuestion third) {
    }

    private Midway midway() {
        UUID owner = newUser();
        Instant now = clock.instant();
        Interview interview = fixture.inState(owner, InterviewState.QUESTIONING, now);
        UUID id = interview.getId();
        InterviewQuestion intro = introductionOf(id);
        fixture.answer(intro, "I build backends.", now);
        InterviewQuestion first = fixture.askTechnical(id, "java", "concurrency", now);
        fixture.answer(first, "volatile gives visibility.", now);
        InterviewQuestion followUp = fixture.askFollowUp(id, first, Section.QUESTIONING, now);
        fixture.answer(followUp, "synchronized locks.", now);
        InterviewQuestion third = fixture.askTechnical(id, "java", "collections", now);
        return new Midway(owner, id, intro, first, followUp, third);
    }

    // ---- session view ------------------------------------------------------------------------------------------------

    @Test
    void anInterviewThatWasNotStartedHasNoSectionNoQuestionAndTheWholeDurationLeft() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);

        getAs(ada, "/interviews/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.state").value("INIT"))
                .andExpect(jsonPath("$.serverTime").value(clock.instant().toString()))
                .andExpect(jsonPath("$.startedAt").value((Object) null))
                .andExpect(jsonPath("$.deadlineAt").value((Object) null))
                .andExpect(jsonPath("$.endReason").value((Object) null))
                .andExpect(jsonPath("$.progress.section").value((Object) null))
                .andExpect(jsonPath("$.progress.sectionIndex").value((Object) null))
                .andExpect(jsonPath("$.progress.sectionCount").value(2))
                .andExpect(jsonPath("$.progress.questionsAnswered").value(0))
                .andExpect(jsonPath("$.progress.plannedQuestionsInSection").value((Object) null))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(45 * 60))
                .andExpect(jsonPath("$.currentQuestion").value((Object) null))
                .andExpect(jsonPath("$.awaitingNextQuestion").value(false))
                .andExpect(jsonPath("$.interviewComplete").value(false))
                .andExpect(jsonPath("$.configuration.interviewType").value("TECHNICAL"))
                .andExpect(jsonPath("$.configuration.skills[*].slug", contains("java", "spring-boot")));
    }

    @Test
    void theSessionCountsTheAnsweredMainQuestionsOfTheCurrentSectionAndTheTimeToTheDeadline() throws Exception {
        Midway m = midway();
        clock.advance(Duration.ofMinutes(7));

        getAs(m.owner(), "/interviews/" + m.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUESTIONING"))
                .andExpect(jsonPath("$.progress.section").value("QUESTIONING"))
                .andExpect(jsonPath("$.progress.sectionIndex").value(1))
                .andExpect(jsonPath("$.progress.sectionCount").value(4))
                .andExpect(jsonPath("$.progress.questionsAnswered").value(1))
                .andExpect(jsonPath("$.progress.plannedQuestionsInSection").value(6))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(53 * 60))
                .andExpect(jsonPath("$.serverTime").value(clock.instant().toString()))
                .andExpect(jsonPath("$.currentQuestion.id").value(m.third().getId().toString()))
                .andExpect(jsonPath("$.currentQuestion.sequenceNo").value(4))
                .andExpect(jsonPath("$.currentQuestion.kind").value("TECHNICAL"))
                .andExpect(jsonPath("$.currentQuestion.isFollowUp").value(false))
                .andExpect(jsonPath("$.currentQuestion.section").value("QUESTIONING"))
                .andExpect(jsonPath("$.currentQuestion.topic.skill").value("Java"))
                .andExpect(jsonPath("$.currentQuestion.topic.name").value("Collections"))
                .andExpect(jsonPath("$.currentQuestion.answerFormat").value("TEXT"))
                .andExpect(jsonPath("$.currentQuestion.answered").value(false));
    }

    @Test
    void aSkippedMainQuestionCountsAsAnsweredAndAFollowUpDoesNot() throws Exception {
        Midway m = midway();
        fixture.skip(m.third(), clock.instant());

        getAs(m.owner(), "/interviews/" + m.id())
                .andExpect(jsonPath("$.progress.questionsAnswered").value(2));
    }

    @Test
    void aFollowUpIsInTheSectionOfItsThreadAndItsOwnFormatMatchesThatSection() throws Exception {
        UUID ada = newUser();
        Instant now = clock.instant();
        Interview coding = fixture.inState(ada, InterviewState.CODING, now);
        UUID id = coding.getId();
        InterviewQuestion problem = fixture.askCoding(id, now);
        fixture.answer(problem, "int sum(int[] v) { return 0; }", now);
        fixture.move(id, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, now);
        fixture.askFollowUp(id, problem, Section.CODING, now);

        getAs(ada, "/interviews/" + id)
                .andExpect(jsonPath("$.state").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.progress.section").value("CODING"))
                .andExpect(jsonPath("$.progress.sectionIndex").value(2))
                .andExpect(jsonPath("$.progress.questionsAnswered").value(1))
                .andExpect(jsonPath("$.progress.plannedQuestionsInSection").value(1))
                .andExpect(jsonPath("$.currentQuestion.isFollowUp").value(true))
                .andExpect(jsonPath("$.currentQuestion.kind").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.currentQuestion.answerFormat").value("CODE"))
                .andExpect(jsonPath("$.currentQuestion.details").value((Object) null));
    }

    @Test
    void aCodingQuestionCarriesItsMaterialAndIsAnsweredWithCode() throws Exception {
        UUID ada = newUser();
        Instant now = clock.instant();
        UUID id = fixture.inState(ada, InterviewState.CODING, now).getId();
        InterviewQuestion problem = fixture.askCoding(id, now);

        getAs(ada, "/interviews/" + id)
                .andExpect(jsonPath("$.currentQuestion.id").value(problem.getId().toString()))
                .andExpect(jsonPath("$.currentQuestion.kind").value("CODING"))
                .andExpect(jsonPath("$.currentQuestion.answerFormat").value("CODE"))
                .andExpect(jsonPath("$.currentQuestion.topic").value((Object) null))
                .andExpect(jsonPath("$.currentQuestion.details.language").value("java"))
                .andExpect(jsonPath("$.currentQuestion.details.constraints[0]").value("1 <= n <= 10^5"))
                .andExpect(jsonPath("$.currentQuestion.details.examples[0].input").value("[1,2,3]"))
                .andExpect(jsonPath("$.currentQuestion.details.examples[0].output").value("6"))
                .andExpect(jsonPath("$.currentQuestion.details.examples[0].explanation").value("1+2+3"))
                .andExpect(jsonPath("$.currentQuestion.details.starterCode").value("int sum(int[] values)"));
    }

    @Test
    void theNextQuestionIsAwaitedOnlyWhileAnAnswerAcceptingInterviewHasADecidedStepAndNoOpenQuestion() throws Exception {
        Midway m = midway();
        Instant now = clock.instant();
        PendingStep step = PendingStep.nextTopic(Section.QUESTIONING, "java/collections", 3);

        fixture.setMemory(m.id(), InterviewMemory.empty().withPendingStep(step), now);
        getAs(m.owner(), "/interviews/" + m.id())
                .andExpect(jsonPath("$.awaitingNextQuestion").value(false))
                .andExpect(jsonPath("$.currentQuestion.id").value(m.third().getId().toString()));

        fixture.clearCurrentQuestion(m.id(), now);
        getAs(m.owner(), "/interviews/" + m.id())
                .andExpect(jsonPath("$.awaitingNextQuestion").value(true))
                .andExpect(jsonPath("$.currentQuestion").value((Object) null))
                .andExpect(jsonPath("$.interviewComplete").value(false));
        getAs(m.owner(), "/interviews/" + m.id() + "/state").andExpect(jsonPath("$.awaitingNextQuestion").value(true));

        fixture.setMemory(m.id(), InterviewMemory.empty(), now);
        getAs(m.owner(), "/interviews/" + m.id()).andExpect(jsonPath("$.awaitingNextQuestion").value(false));
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"FINAL_EVALUATION", "COMPLETED"})
    void onceTheQuestionPhaseIsOverTheInterviewIsCompleteWithNoTimeLeftAndNothingAwaited(InterviewState state) throws Exception {
        UUID ada = newUser();
        Instant now = clock.instant();
        UUID id = fixture.inState(ada, InterviewState.QUESTIONING, now).getId();
        fixture.setMemory(
                id,
                InterviewMemory.empty().withPendingStep(PendingStep.nextTopic(Section.QUESTIONING, "java/collections", 3)),
                now);
        fixture.move(id, InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED, now);
        if (state == InterviewState.COMPLETED) {
            fixture.move(id, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, now);
        }

        getAs(ada, "/interviews/" + id)
                .andExpect(jsonPath("$.state").value(state.name()))
                .andExpect(jsonPath("$.interviewComplete").value(true))
                .andExpect(jsonPath("$.awaitingNextQuestion").value(false))
                .andExpect(jsonPath("$.currentQuestion").value((Object) null))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(0))
                .andExpect(jsonPath("$.endReason").value("CANDIDATE_ENDED"));
    }

    @Test
    void afterTheQuestionPhaseTheProgressStaysOnTheLastSectionThatWasStarted() throws Exception {
        UUID ada = newUser();
        Instant now = clock.instant();
        UUID id = fixture.inState(ada, InterviewState.CODING, now).getId();
        InterviewQuestion problem = fixture.askCoding(id, now);
        fixture.answer(problem, "code", now);
        fixture.setMemory(
                id,
                new InterviewMemory(
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        Map.of(
                                Section.INTRODUCTION, new SectionProgress(1, now),
                                Section.QUESTIONING, new SectionProgress(6, now),
                                Section.CODING, new SectionProgress(1, now)),
                        null),
                now);
        fixture.move(id, InterviewState.FINAL_EVALUATION, InterviewEvent.SECTION_COMPLETE, now);

        getAs(ada, "/interviews/" + id)
                .andExpect(jsonPath("$.progress.section").value("CODING"))
                .andExpect(jsonPath("$.progress.sectionIndex").value(2))
                .andExpect(jsonPath("$.progress.questionsAnswered").value(1))
                .andExpect(jsonPath("$.progress.plannedQuestionsInSection").value(1));
    }

    @Test
    void anAbandonedInterviewHasNoSectionAndNoTimeLeft() throws Exception {
        UUID ada = newUser();
        UUID id = fixture.inState(ada, InterviewState.ABANDONED, clock.instant()).getId();

        getAs(ada, "/interviews/" + id)
                .andExpect(jsonPath("$.state").value("ABANDONED"))
                .andExpect(jsonPath("$.endReason").value("ABANDONED"))
                .andExpect(jsonPath("$.interviewComplete").value(false))
                .andExpect(jsonPath("$.progress.section").value((Object) null))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(0));
    }

    // ---- state -------------------------------------------------------------------------------------------------------

    @Test
    void theStateIsTheLightweightViewWithoutQuestionOrConfiguration() throws Exception {
        Midway m = midway();
        clock.advance(Duration.ofMinutes(3));
        Interview stored = fixture.reload(m.id());

        JsonNode state = body(getAs(m.owner(), "/interviews/" + m.id() + "/state")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUESTIONING"))
                .andExpect(jsonPath("$.deadlineAt").value(stored.getDeadlineAt().toString()))
                .andExpect(jsonPath("$.serverTime").value(clock.instant().toString()))
                .andExpect(jsonPath("$.version").value(stored.getVersion()))
                .andExpect(jsonPath("$.awaitingNextQuestion").value(false))
                .andExpect(jsonPath("$.interviewComplete").value(false))
                .andExpect(jsonPath("$.progress.section").value("QUESTIONING"))
                .andExpect(jsonPath("$.progress.questionsAnswered").value(1))
                .andExpect(jsonPath("$.progress.plannedQuestionsInSection").value(6))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(57 * 60)));

        assertThat(state.propertyNames()).containsExactlyInAnyOrder(
                "state", "progress", "deadlineAt", "serverTime", "version", "awaitingNextQuestion", "interviewComplete");
    }

    @Test
    void theStateOfAnInterviewThatWasNotStartedShowsTheWholeDuration() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada, FULL_LOOP_CONFIGURATION);

        getAs(ada, "/interviews/" + id + "/state")
                .andExpect(jsonPath("$.state").value("INIT"))
                .andExpect(jsonPath("$.deadlineAt").value((Object) null))
                .andExpect(jsonPath("$.progress.remainingSeconds").value(60 * 60))
                .andExpect(jsonPath("$.progress.sectionCount").value(4));
    }

    @Test
    void theVersionGrowsWithEveryChangeAndTheSessionAndTheStateAgreeOnIt() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);
        long before = body(getAs(ada, "/interviews/" + id + "/state")).get("version").asLong();

        postAs(ada, "/interviews/" + id + "/start").andExpect(status().isOk());

        long after = body(getAs(ada, "/interviews/" + id + "/state")).get("version").asLong();
        assertThat(after).isGreaterThan(before);
        assertThat(body(getAs(ada, "/interviews/" + id)).get("version").asLong()).isEqualTo(after);
    }

    // ---- questions -----------------------------------------------------------------------------------------------------

    @Test
    void theQuestionsAreListedInTheOrderTheyWereAskedWithTheirAnsweredFlags() throws Exception {
        Midway m = midway();

        getAs(m.owner(), "/interviews/" + m.id() + "/questions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].id", contains(
                        m.intro().getId().toString(), m.first().getId().toString(),
                        m.followUp().getId().toString(), m.third().getId().toString())))
                .andExpect(jsonPath("$[*].sequenceNo", contains(1, 2, 3, 4)))
                .andExpect(jsonPath("$[*].kind", contains("INTRO", "TECHNICAL", "FOLLOW_UP", "TECHNICAL")))
                .andExpect(jsonPath("$[*].isFollowUp", contains(false, false, true, false)))
                .andExpect(jsonPath("$[*].answered", contains(true, true, true, false)))
                .andExpect(jsonPath("$[0].topic").value((Object) null))
                .andExpect(jsonPath("$[1].topic.name").value("Concurrency"))
                .andExpect(jsonPath("$[2].topic.name").value("Concurrency"))
                .andExpect(jsonPath("$[3].topic.name").value("Collections"));
    }

    @Test
    void aSkippedQuestionIsAnswered() throws Exception {
        Midway m = midway();
        fixture.skip(m.third(), clock.instant());

        getAs(m.owner(), "/interviews/" + m.id() + "/questions")
                .andExpect(jsonPath("$[3].answered").value(true));
    }

    @Test
    void aNewInterviewHasNoQuestionsAndTheListIsEmpty() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);

        getAs(ada, "/interviews/" + id + "/questions").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void theCurrentQuestionIsTheOpenOneAndMissingWhenNoneIsOpen() throws Exception {
        Midway m = midway();
        String base = "/interviews/" + m.id() + "/questions/current";

        getAs(m.owner(), base)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(m.third().getId().toString()))
                .andExpect(jsonPath("$.answered").value(false));

        fixture.clearCurrentQuestion(m.id(), clock.instant());
        getAs(m.owner(), base)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INIT", "FINAL_EVALUATION", "COMPLETED", "ABANDONED"})
    void thereIsNoCurrentQuestionBeforeTheStartOrAfterTheQuestionPhase(InterviewState state) throws Exception {
        UUID ada = newUser();
        UUID id = fixture.inState(ada, state, clock.instant()).getId();

        getAs(ada, "/interviews/" + id + "/questions/current").andExpect(status().isNotFound());
    }

    @Test
    void oneQuestionIsReadByIdWithItsAnsweredFlag() throws Exception {
        Midway m = midway();

        getAs(m.owner(), "/interviews/" + m.id() + "/questions/" + m.first().getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(m.first().getId().toString()))
                .andExpect(jsonPath("$.sequenceNo").value(2))
                .andExpect(jsonPath("$.answered").value(true))
                .andExpect(jsonPath("$.prompt").value("Explain how volatile differs from synchronized."))
                .andExpect(jsonPath("$.topic.skill").value("Java"));
        getAs(m.owner(), "/interviews/" + m.id() + "/questions/" + m.third().getId())
                .andExpect(jsonPath("$.answered").value(false));
    }

    @Test
    void aQuestionOfAnotherInterviewOfTheSameUserIsNotFound() throws Exception {
        Midway m = midway();
        UUID other = fixture.inState(m.owner(), InterviewState.QUESTIONING, clock.instant()).getId();

        getAs(m.owner(), "/interviews/" + other + "/questions/" + m.first().getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        getAs(m.owner(), "/interviews/" + m.id() + "/questions/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // ---- transcript ----------------------------------------------------------------------------------------------------

    @Test
    void theTranscriptPairsEveryQuestionWithItsAnswerAndLeavesOpenOnesEmpty() throws Exception {
        Midway m = midway();
        fixture.skip(m.third(), clock.instant());
        InterviewQuestion fourth = fixture.askTechnical(m.id(), "java", "generics", clock.instant());

        getAs(m.owner(), "/interviews/" + m.id() + "/transcript")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interviewId").value(m.id().toString()))
                .andExpect(jsonPath("$.state").value("QUESTIONING"))
                .andExpect(jsonPath("$.entries", hasSize(5)))
                .andExpect(jsonPath("$.entries[*].question.sequenceNo", contains(1, 2, 3, 4, 5)))
                .andExpect(jsonPath("$.entries[0].answer.content").value("I build backends."))
                .andExpect(jsonPath("$.entries[0].answer.skipped").value(false))
                .andExpect(jsonPath("$.entries[1].answer.content").value("volatile gives visibility."))
                .andExpect(jsonPath("$.entries[1].answer.submittedAt").exists())
                .andExpect(jsonPath("$.entries[3].answer.skipped").value(true))
                .andExpect(jsonPath("$.entries[3].answer.content").value(""))
                .andExpect(jsonPath("$.entries[4].question.id").value(fourth.getId().toString()))
                .andExpect(jsonPath("$.entries[4].answer").value((Object) null))
                .andExpect(jsonPath("$.entries[*].feedback", contains(null, null, null, null, null)));
    }

    @Test
    void feedbackStaysHiddenWhileTheInterviewIsBeingEvaluatedEvenWhenEvaluationsAreStored() throws Exception {
        UUID ada = newUser();
        UUID id = evaluatedInterview(ada, InterviewState.FINAL_EVALUATION, false);

        String raw = getAs(ada, "/interviews/" + id + "/transcript")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINAL_EVALUATION"))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[1].answer.content").value("volatile gives visibility."))
                .andExpect(jsonPath("$.entries[*].feedback", contains(null, null)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(raw)
                .doesNotContain(SECRET_RATIONALE)
                .doesNotContain("Solid on visibility")
                .doesNotContain("57.5")
                .doesNotContain("synchronized adds mutual exclusion");
    }

    @Test
    void feedbackAppearsWhenTheInterviewIsCompletedWithTheKeyPointTextAndTheRatings() throws Exception {
        UUID ada = newUser();
        UUID id = evaluatedInterview(ada, InterviewState.COMPLETED, false);

        getAs(ada, "/interviews/" + id + "/transcript")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.entries[0].feedback").value((Object) null))
                .andExpect(jsonPath("$.entries[1].feedback.overallScore").value(57.5))
                .andExpect(jsonPath("$.entries[1].feedback.summary").value("Solid on visibility, missed mutual exclusion."))
                .andExpect(jsonPath("$.entries[1].feedback.criteria", hasSize(2)))
                .andExpect(jsonPath("$.entries[1].feedback.criteria[0].criterion").value("CORRECTNESS"))
                .andExpect(jsonPath("$.entries[1].feedback.criteria[0].rating").value(3))
                .andExpect(jsonPath("$.entries[1].feedback.criteria[0].rationale").value("Mostly right, " + SECRET_RATIONALE + "."))
                .andExpect(jsonPath("$.entries[1].feedback.keyPoints", hasSize(2)))
                .andExpect(jsonPath("$.entries[1].feedback.keyPoints[0].text").value("volatile guarantees visibility"))
                .andExpect(jsonPath("$.entries[1].feedback.keyPoints[0].status").value("COVERED"))
                .andExpect(jsonPath("$.entries[1].feedback.keyPoints[1].text").value("synchronized adds mutual exclusion"))
                .andExpect(jsonPath("$.entries[1].feedback.keyPoints[1].status").value("MISSING"));
    }

    @Test
    void aCompletedInterviewShowsNoFeedbackForAnAnswerThatWasNeverEvaluated() throws Exception {
        UUID ada = newUser();
        UUID id = evaluatedInterview(ada, InterviewState.COMPLETED, true);

        getAs(ada, "/interviews/" + id + "/transcript")
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[2].answer.skipped").value(true))
                .andExpect(jsonPath("$.entries[2].feedback").value((Object) null));
    }

    @Test
    void theTranscriptOfAnInterviewThatWasNotStartedIsEmpty() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);

        getAs(ada, "/interviews/" + id + "/transcript")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INIT"))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    /**
     * An interview in {@code finalState} with the introduction and one technical question answered, the latter evaluated,
     * and optionally one more question that was skipped (a skip has no evaluation).
     */
    private UUID evaluatedInterview(UUID owner, InterviewState finalState, boolean withSkippedQuestion) {
        Instant now = clock.instant();
        UUID id = fixture.inState(owner, InterviewState.QUESTIONING, now).getId();
        InterviewQuestion intro = introductionOf(id);
        fixture.answer(intro, "I build backends.", now);
        InterviewQuestion technical = fixture.askTechnical(id, "java", "concurrency", now);
        CandidateAnswer answer = fixture.answer(technical, "volatile gives visibility.", now);
        fixture.evaluate(answer, now);
        if (withSkippedQuestion) {
            fixture.skip(fixture.askTechnical(id, "java", "generics", now), now);
        }
        fixture.move(id, InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED, now);
        if (finalState == InterviewState.COMPLETED) {
            fixture.move(id, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, now);
        }
        return id;
    }

    // ---- list ----------------------------------------------------------------------------------------------------------

    @Test
    void theListIsPagedNewestFirstAndHoldsOnlyTheCallersInterviews() throws Exception {
        UUID ada = newUser();
        UUID bob = newUser();
        List<UUID> adas = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            adas.add(createInterview(ada));
            clock.advance(Duration.ofMinutes(1));
            createInterview(bob);
            clock.advance(Duration.ofMinutes(1));
        }

        getAs(ada, "/interviews?page=0&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[*].id", contains(adas.get(2).toString(), adas.get(1).toString())));
        getAs(ada, "/interviews?page=1&size=2").andExpect(jsonPath("$.items[*].id", contains(adas.get(0).toString())));
        getAs(ada, "/interviews").andExpect(jsonPath("$.items", hasSize(3))).andExpect(jsonPath("$.size").value(20));
        getAs(bob, "/interviews").andExpect(jsonPath("$.totalItems").value(3));
        getAs(newUser(), "/interviews").andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void theListCanBeFilteredByStateAndShowsTheConfigurationAndTheTimes() throws Exception {
        UUID ada = newUser();
        UUID fresh = createInterview(ada, FULL_LOOP_CONFIGURATION);
        UUID started = startedInterview(ada);
        UUID abandoned = createInterview(ada);
        postAs(ada, "/interviews/" + abandoned + "/abandon").andExpect(status().isOk());

        getAs(ada, "/interviews?state=INIT")
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(fresh.toString()))
                .andExpect(jsonPath("$.items[0].state").value("INIT"))
                .andExpect(jsonPath("$.items[0].configuration.interviewType").value("FULL_LOOP"))
                .andExpect(jsonPath("$.items[0].configuration.codingLanguage").value("java"))
                .andExpect(jsonPath("$.items[0].configuration.skills[*].slug", contains("java", "postgresql")))
                .andExpect(jsonPath("$.items[0].createdAt").value(TEST_START))
                .andExpect(jsonPath("$.items[0].startedAt").value((Object) null))
                .andExpect(jsonPath("$.items[0].endedAt").value((Object) null))
                .andExpect(jsonPath("$.items[0].endReason").value((Object) null));
        getAs(ada, "/interviews?state=INTRODUCTION")
                .andExpect(jsonPath("$.items[*].id", contains(started.toString())))
                .andExpect(jsonPath("$.items[0].startedAt").value(TEST_START));
        getAs(ada, "/interviews?state=ABANDONED")
                .andExpect(jsonPath("$.items[*].id", contains(abandoned.toString())))
                .andExpect(jsonPath("$.items[0].endedAt").value(TEST_START))
                .andExpect(jsonPath("$.items[0].endReason").value("ABANDONED"));
        getAs(ada, "/interviews?state=COMPLETED").andExpect(jsonPath("$.items", hasSize(0)));
        getAs(ada, "/interviews").andExpect(jsonPath("$.totalItems").value(3));
    }

    private static final String TEST_START = com.techinterviewer.TestClock.START.toString();

    @Test
    void anUnknownStateAndAnOutOfRangePageAreRefused() throws Exception {
        UUID ada = newUser();

        getAs(ada, "/interviews?state=NOPE").andExpect(status().isBadRequest());
        getAs(ada, "/interviews?size=0").andExpect(status().isBadRequest());
        getAs(ada, "/interviews?size=101").andExpect(status().isBadRequest());
        getAs(ada, "/interviews?page=-1").andExpect(status().isBadRequest());
        getAs(ada, "/interviews?size=100").andExpect(status().isOk());
    }
}
