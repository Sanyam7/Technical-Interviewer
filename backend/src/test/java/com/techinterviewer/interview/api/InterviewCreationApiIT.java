package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.config.AppProperties;
import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

class InterviewCreationApiIT extends InterviewApiTestBase {

    private static final String INLINE_TECHNICAL = "{\"configuration\":" + TECHNICAL_CONFIGURATION + "}";

    private long interviewCount(UUID candidate) {
        return jdbc.sql("SELECT count(*) FROM interviews WHERE candidate_id = :c").param("c", candidate).query(Long.class).single();
    }

    private long configurationCount(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM interview_configurations WHERE owner_user_id = :o").param("o", owner).query(Long.class).single();
    }

    // ---- what is created ---------------------------------------------------------------------------------------

    @Test
    void anInlineConfigurationCreatesAnInterviewInInitWithItsPlanAndIsSavedForTheCaller() throws Exception {
        UUID ada = newUser();

        ResultActions created = postAs(ada, "/interviews", "{\"configuration\":" + FULL_LOOP_CONFIGURATION + "}")
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state").value("INIT"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.configuration.role").value("BACKEND"))
                .andExpect(jsonPath("$.configuration.experienceLevel").value("SENIOR"))
                .andExpect(jsonPath("$.configuration.difficulty").value("HARD"))
                .andExpect(jsonPath("$.configuration.durationMinutes").value(60))
                .andExpect(jsonPath("$.configuration.interviewType").value("FULL_LOOP"))
                .andExpect(jsonPath("$.configuration.codingLanguage").value("java"))
                .andExpect(jsonPath("$.configuration.skills[0].slug").value("java"))
                .andExpect(jsonPath("$.configuration.skills[1].slug").value("postgresql"))
                .andExpect(jsonPath("$.plan.sections", hasSize(4)))
                .andExpect(jsonPath("$.plan.sections[0].section").value("INTRODUCTION"))
                .andExpect(jsonPath("$.plan.sections[1].section").value("QUESTIONING"))
                .andExpect(jsonPath("$.plan.sections[2].section").value("CODING"))
                .andExpect(jsonPath("$.plan.sections[3].section").value("SYSTEM_DESIGN"));
        JsonNode body = body(created);
        UUID id = UUID.fromString(body.get("id").asString());
        UUID configurationId = UUID.fromString(body.get("configuration").get("id").asString());

        created.andExpect(header().string("Location", API + "/interviews/" + id));
        assertThat(configurationCount(ada)).isEqualTo(1);
        assertThat(configurations.findByIdAndOwnerUserId(configurationId, ada)).isPresent();
        Interview stored = interviews.findByIdAndCandidateId(id, ada).orElseThrow();
        assertThat(stored.getConfigurationId()).isEqualTo(configurationId);
        assertThat(stored.getState()).isEqualTo(InterviewState.INIT);
        assertThat(stored.getVersion()).isZero();
        assertThat(stored.getMemory()).isEqualTo(InterviewMemory.empty());
        assertThat(stored.getStartedAt()).isNull();
        assertThat(stored.getDeadlineAt()).isNull();
        assertThat(stored.getEndedAt()).isNull();
        assertThat(stored.getCurrentQuestionId()).isNull();
        assertThat(stored.getCreatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void thePublicPlanShowsTheMinutesAndPlannedQuestionsOfTheStoredPlanAndNothingElse() throws Exception {
        UUID ada = newUser();

        JsonNode body = body(postAs(ada, "/interviews", "{\"configuration\":" + FULL_LOOP_CONFIGURATION + "}").andExpect(status().isCreated()));

        InterviewPlan stored = interviews.findByIdAndCandidateId(UUID.fromString(body.get("id").asString()), ada).orElseThrow().getPlan();
        JsonNode sections = body.get("plan").get("sections");
        assertThat(sections).hasSize(stored.sections().size());
        for (int i = 0; i < stored.sections().size(); i++) {
            SectionPlan expected = stored.sections().get(i);
            assertThat(sections.get(i).get("section").asString()).isEqualTo(expected.section().name());
            assertThat(sections.get(i).get("minutes").asInt()).isEqualTo(expected.minutes());
            assertThat(sections.get(i).get("plannedQuestions").asInt()).isEqualTo(expected.slots());
            assertThat(sections.get(i).propertyNames()).containsExactlyInAnyOrder("section", "minutes", "plannedQuestions");
        }
        assertThat(stored.sections().stream().filter(section -> section.section() == Section.QUESTIONING).findFirst().orElseThrow().topicQuotas())
                .as("the stored plan keeps its quotas, the response does not show them").isNotEmpty();
        assertThat(body.get("plan").propertyNames()).containsExactly("sections");
        assertThat(body.toString()).doesNotContain("topicQuotas", "maxFollowUpDepth");
    }

    @Test
    void theFirstRowOfTheTransitionLogIsTheCreationFromNothingToInit() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);

        List<InterviewStateTransition> log = transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(id);

        assertThat(log).hasSize(1);
        assertThat(log.getFirst().getFromState()).isNull();
        assertThat(log.getFirst().getToState()).isEqualTo(InterviewState.INIT);
        assertThat(log.getFirst().getEvent().name()).isEqualTo("CREATE");
        assertThat(log.getFirst().getOccurredAt()).isEqualTo(clock.instant());
        assertThat(questions.findByInterviewIdOrderBySequenceNoAsc(id)).isEmpty();
    }

    @Test
    void aSavedConfigurationIsUsedWithoutCreatingANewOne() throws Exception {
        UUID ada = newUser();
        UUID configuration = saveConfiguration(ada, FULL_LOOP_CONFIGURATION);

        ResultActions created = postAs(ada, "/interviews", "{\"configurationId\":\"" + configuration + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configuration.id").value(configuration.toString()))
                .andExpect(jsonPath("$.configuration.interviewType").value("FULL_LOOP"));
        postAs(ada, "/interviews", "{\"configurationId\":\"" + configuration + "\"}").andExpect(status().isCreated());

        UUID id = UUID.fromString(body(created).get("id").asString());
        assertThat(configurationCount(ada)).isEqualTo(1);
        assertThat(interviews.findByIdAndCandidateId(id, ada).orElseThrow().getConfigurationId()).isEqualTo(configuration);
        assertThat(interviewCount(ada)).isEqualTo(2);
    }

    @Test
    void theCreatedInterviewAppearsInTheListAndItsSessionIsReadable() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);

        getAs(ada, "/interviews").andExpect(jsonPath("$.items[0].id").value(id.toString()));
        getAs(ada, "/interviews/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("INIT"));
    }

    // ---- the two sources -----------------------------------------------------------------------------------------

    @Test
    void bothSourcesAtOnceAreRefusedOnTheConfigurationField() throws Exception {
        UUID ada = newUser();
        UUID configuration = saveConfiguration(ada, TECHNICAL_CONFIGURATION);

        postAs(ada, "/interviews", "{\"configurationId\":\"" + configuration + "\",\"configuration\":" + TECHNICAL_CONFIGURATION + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("configuration"))
                .andExpect(jsonPath("$.errors[0].message").value("Provide exactly one of configurationId and configuration."));
        assertThat(interviewCount(ada)).isZero();
        assertThat(configurationCount(ada)).isEqualTo(1);
    }

    @Test
    void neitherSourceIsRefusedOnTheConfigurationIdField() throws Exception {
        UUID ada = newUser();

        postAs(ada, "/interviews", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("configurationId"))
                .andExpect(jsonPath("$.errors[0].message").value("Provide exactly one of configurationId and configuration."));
        assertThat(interviewCount(ada)).isZero();
    }

    @Test
    void aConfigurationOfSomebodyElseAndAMissingOneAreIndistinguishable404s() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();
        UUID gracesConfiguration = saveConfiguration(grace, TECHNICAL_CONFIGURATION);

        JsonNode foreign = body(postAs(ada, "/interviews", "{\"configurationId\":\"" + gracesConfiguration + "\"}")
                .andExpect(status().isNotFound()));
        JsonNode missing = body(postAs(ada, "/interviews", "{\"configurationId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isNotFound()));

        assertThat(foreign.get("code").asString()).isEqualTo("NOT_FOUND").isEqualTo(missing.get("code").asString());
        assertThat(foreign.get("detail")).isEqualTo(missing.get("detail"));
        assertThat(interviewCount(ada)).isZero();
        assertThat(interviewCount(grace)).isZero();
    }

    @Test
    void anInvalidInlineConfigurationIsReportedOnPrefixedFieldsAndCreatesNothing() throws Exception {
        UUID ada = newUser();
        String broken = with(with(TECHNICAL_CONFIGURATION, "durationMinutes", 20), "skills", List.of("java", "java", "cobol"));

        postAs(ada, "/interviews", "{\"configuration\":" + broken + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", contains(
                        "configuration.durationMinutes", "configuration.skills[1]", "configuration.skills[2]")));

        assertThat(interviewCount(ada)).isZero();
        assertThat(configurationCount(ada)).isZero();
    }

    @Test
    void annotationViolationsOfTheInlineConfigurationAreFieldErrorsToo() throws Exception {
        postAs(newUser(), "/interviews", "{\"configuration\":{}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("configuration.skills")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("configuration.role")));
    }

    @Test
    void theCandidateCannotBeChosenInTheBody() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();

        postAs(ada, "/interviews", "{\"candidateId\":\"" + grace + "\",\"configuration\":" + TECHNICAL_CONFIGURATION + "}")
                .andExpect(status().isBadRequest());

        assertThat(interviewCount(grace)).isZero();
        assertThat(interviewCount(ada)).isZero();
    }

    // ---- planning ---------------------------------------------------------------------------------------------------

    private String configurationWithTopicsOutsideTheBand() {
        long skill = catalog.skill("expert-only", "Expert Only", true);
        catalog.topic(skill, "deep-dive", "Deep dive", 5, 5);
        return with(with(TECHNICAL_CONFIGURATION, "skills", List.of("expert-only")), "difficulty", "EASY");
    }

    @Test
    void anInlineConfigurationWhoseSkillsHaveNoTopicInTheDifficultyBandIsA400AndNothingIsSaved() throws Exception {
        UUID ada = newUser();

        postAs(ada, "/interviews", "{\"configuration\":" + configurationWithTopicsOutsideTheBand() + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("configuration"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "None of the topics of the selected skills fits the requested difficulty. Choose other skills or another difficulty."));

        assertThat(interviewCount(ada)).isZero();
        assertThat(configurationCount(ada)).as("the inline configuration is rolled back with the interview").isZero();
    }

    @Test
    void aSavedConfigurationWhoseSkillsHaveNoTopicInTheDifficultyBandIsA400OnConfigurationId() throws Exception {
        UUID ada = newUser();
        UUID configuration = saveConfiguration(ada, configurationWithTopicsOutsideTheBand());

        postAs(ada, "/interviews", "{\"configurationId\":\"" + configuration + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("configurationId"));

        assertThat(interviewCount(ada)).isZero();
        assertThat(configurationCount(ada)).isEqualTo(1);
    }

    @Test
    void aSkillRetiredAfterTheConfigurationWasSavedTakesNoPartInThePlanButStaysOnTheConfiguration() throws Exception {
        UUID ada = newUser();
        long retiring = catalog.skill("retiring", "Retiring", true);
        catalog.topic(retiring, "only-topic", "Only topic", 1, 5);
        UUID configuration = saveConfiguration(ada, with(TECHNICAL_CONFIGURATION, "skills", List.of("java", "retiring")));
        catalog.deactivate(retiring);

        ResultActions created = postAs(ada, "/interviews", "{\"configurationId\":\"" + configuration + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configuration.skills[*].slug", contains("java", "retiring")));

        UUID id = UUID.fromString(body(created).get("id").asString());
        InterviewPlan plan = interviews.findByIdAndCandidateId(id, ada).orElseThrow().getPlan();
        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas().keySet())
                .isNotEmpty().allSatisfy(topic -> assertThat(topic).startsWith("java/"));
    }

    @Test
    void theSameConfigurationAlwaysGetsTheSamePlan() throws Exception {
        UUID ada = newUser();
        UUID configuration = saveConfiguration(ada, FULL_LOOP_CONFIGURATION);

        JsonNode first = body(postAs(ada, "/interviews", "{\"configurationId\":\"" + configuration + "\"}"));
        JsonNode second = body(postAs(ada, "/interviews", "{\"configurationId\":\"" + configuration + "\"}"));

        assertThat(first.get("plan")).isEqualTo(second.get("plan"));
        InterviewPlan a = interviews.findByIdAndCandidateId(UUID.fromString(first.get("id").asString()), ada).orElseThrow().getPlan();
        InterviewPlan b = interviews.findByIdAndCandidateId(UUID.fromString(second.get("id").asString()), ada).orElseThrow().getPlan();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void theStoredPlanAllowsTheConfiguredNumberOfFollowUpsPerQuestion() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada, FULL_LOOP_CONFIGURATION);

        assertThat(interviews.findByIdAndCandidateId(id, ada).orElseThrow().getPlan().maxFollowUpDepth())
                .isEqualTo(context.getBean(AppProperties.class).interview().maxFollowUpDepth())
                .isEqualTo(2);
    }

    // ---- the daily limit -----------------------------------------------------------------------------------------

    private void createInterviews(UUID user, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            postAs(user, "/interviews", INLINE_TECHNICAL).andExpect(status().isCreated());
        }
    }

    @Test
    void theTenthInterviewOfTheDayIsAllowedAndTheEleventhIsA429WithRetryAfterToMidnightUtc() throws Exception {
        UUID ada = newUser();
        createInterviews(ada, 10);

        postAs(ada, "/interviews", INLINE_TECHNICAL)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DAILY_INTERVIEW_LIMIT"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().string("Retry-After", String.valueOf(Duration.ofHours(12).toSeconds())));

        assertThat(interviewCount(ada)).isEqualTo(10);
        assertThat(configurationCount(ada)).as("the refused request saved no configuration either").isEqualTo(10);
    }

    @Test
    void retryAfterShrinksAsTheDayGoesOnAndNeverReachesZero() throws Exception {
        UUID ada = newUser();
        createInterviews(ada, 10);

        clock.set(Instant.parse("2026-10-05T23:59:59Z"));
        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        clock.set(Instant.parse("2026-10-05T23:59:59.500Z"));
        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        clock.set(Instant.parse("2026-10-05T00:00:00Z"));
        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(header().string("Retry-After", "86400"));
    }

    @Test
    void aNewUtcDayResetsTheLimit() throws Exception {
        UUID ada = newUser();
        createInterviews(ada, 10);
        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests());

        clock.set(Instant.parse("2026-10-06T00:00:00Z"));

        createInterviews(ada, 10);
        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "86400"));
        assertThat(interviewCount(ada)).isEqualTo(20);
    }

    @Test
    void aSecondBeforeMidnightStillCountsForTheOldDayAndAnInterviewAtMidnightCountsForTheNewOne() throws Exception {
        UUID ada = newUser();
        clock.set(Instant.parse("2026-10-05T23:59:59Z"));
        createInterviews(ada, 10);
        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests());

        clock.set(Instant.parse("2026-10-06T00:00:00Z"));
        createInterviews(ada, 1);
        clock.set(Instant.parse("2026-10-06T23:59:59Z"));
        createInterviews(ada, 9);
        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLimitIsPerUser() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();
        createInterviews(ada, 10);

        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests());

        createInterviews(grace, 10);
        postAs(grace, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests());
        assertThat(interviewCount(grace)).isEqualTo(10);
    }

    @Test
    void refusedAndInvalidRequestsDoNotCountAgainstTheLimit() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();
        UUID gracesConfiguration = saveConfiguration(grace, TECHNICAL_CONFIGURATION);
        createInterviews(ada, 9);

        postAs(ada, "/interviews", "{}").andExpect(status().isBadRequest());
        postAs(ada, "/interviews", "{\"configuration\":" + with(TECHNICAL_CONFIGURATION, "durationMinutes", 20) + "}").andExpect(status().isBadRequest());
        postAs(ada, "/interviews", "{\"configurationId\":\"" + gracesConfiguration + "\"}").andExpect(status().isNotFound());
        saveConfiguration(ada, TECHNICAL_CONFIGURATION);

        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isCreated());
        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests());
    }

    @Test
    void abandonedAndStartedInterviewsCountLikeAnyOther() throws Exception {
        UUID ada = newUser();
        UUID first = createInterview(ada);
        postAs(ada, "/interviews/" + first + "/abandon").andExpect(status().isOk());
        UUID second = createInterview(ada);
        postAs(ada, "/interviews/" + second + "/start").andExpect(status().isOk());
        createInterviews(ada, 8);

        postAs(ada, "/interviews", INLINE_TECHNICAL).andExpect(status().isTooManyRequests());
    }

    @Test
    void twelveSimultaneousCreationsOfOneUserYieldExactlyTenInterviews() throws Exception {
        UUID ada = newUser();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                requests.add(() -> postAs(ada, "/interviews", INLINE_TECHNICAL).andReturn().getResponse().getStatus());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(requests)) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(code -> code == 201).hasSize(10);
            assertThat(statuses).filteredOn(code -> code == 429).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(interviewCount(ada)).isEqualTo(10);
        assertThat(configurationCount(ada)).isEqualTo(10);
    }
}
