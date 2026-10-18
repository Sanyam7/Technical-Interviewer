package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

class InterviewConfigurationApiIT extends InterviewApiTestBase {

    private static final String URL = "/interview-configurations";

    private long configurationCount(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM interview_configurations WHERE owner_user_id = :o").param("o", owner).query(Long.class).single();
    }

    // ---- create -----------------------------------------------------------------------------------------------------

    @Test
    void aConfigurationIsSavedWithEveryFieldAndItsSkillsForTheCaller() throws Exception {
        UUID ada = newUser();

        ResultActions created = postAs(ada, URL, FULL_LOOP_CONFIGURATION)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.role").value("BACKEND"))
                .andExpect(jsonPath("$.experienceLevel").value("SENIOR"))
                .andExpect(jsonPath("$.difficulty").value("HARD"))
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andExpect(jsonPath("$.interviewType").value("FULL_LOOP"))
                .andExpect(jsonPath("$.codingLanguage").value("java"))
                .andExpect(jsonPath("$.useResume").value(false))
                .andExpect(jsonPath("$.createdAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.skills", hasSize(2)))
                .andExpect(jsonPath("$.skills[0].slug").value("java"))
                .andExpect(jsonPath("$.skills[0].name").value("Java"))
                .andExpect(jsonPath("$.skills[1].slug").value("postgresql"))
                .andExpect(jsonPath("$.skills[1].name").value("PostgreSQL"));
        UUID id = UUID.fromString(body(created).get("id").asString());
        created.andExpect(header().string("Location", API + URL + "/" + id));

        InterviewConfiguration stored = transaction.execute(status -> configurations.findByIdAndOwnerUserId(id, ada).orElseThrow());
        assertThat(stored.getRole()).isEqualTo(TargetRole.BACKEND);
        assertThat(stored.getExperienceLevel()).isEqualTo(ExperienceLevel.SENIOR);
        assertThat(stored.getDifficulty()).isEqualTo(InterviewDifficulty.HARD);
        assertThat(stored.getDurationMinutes()).isEqualTo(60);
        assertThat(stored.getInterviewType()).isEqualTo(InterviewType.FULL_LOOP);
        assertThat(stored.getCodingLanguage()).isEqualTo("java");
        assertThat(stored.isUseResume()).isFalse();
        assertThat(stored.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(stored.getSkillIds()).containsExactlyInAnyOrder(fixture.skillId("java"), fixture.skillId("postgresql"));
    }

    @Test
    void skillsAreListedByNameWhateverOrderTheyWereSentIn() throws Exception {
        UUID ada = newUser();

        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "skills", List.of("spring-boot", "postgresql", "java")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skills[*].name", contains("Java", "PostgreSQL", "Spring Boot")));
    }

    @Test
    void aTechnicalConfigurationHasNoCodingLanguageAndOneWithoutUseResumeStoresFalse() throws Exception {
        UUID ada = newUser();

        postAs(ada, URL, TECHNICAL_CONFIGURATION)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codingLanguage").value((Object) null))
                .andExpect(jsonPath("$.useResume").value(false));
        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "useResume", false)).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 30, 45, 60, 90})
    void everyOfferedDurationIsAccepted(int minutes) throws Exception {
        postAs(newUser(), URL, with(TECHNICAL_CONFIGURATION, "durationMinutes", minutes))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationMinutes").value(minutes));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 14, 16, 44, 46, 89, 91, 180, -45})
    void aDurationThatIsNotOfferedIsA400OnItsFieldAndNothingIsSaved(int minutes) throws Exception {
        UUID ada = newUser();

        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "durationMinutes", minutes))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("durationMinutes"))
                .andExpect(jsonPath("$.errors[0].message").value("Must be one of [15, 30, 45, 60, 90]."));
        assertThat(configurationCount(ada)).isZero();
    }

    @Test
    void anUnknownSkillIsA400OnItsIndexWithTheSlugInTheMessage() throws Exception {
        UUID ada = newUser();

        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "skills", List.of("java", "cobol")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("skills[1]"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown or inactive skill 'cobol'."));
        assertThat(configurationCount(ada)).isZero();
    }

    @Test
    void aRetiredSkillIsRejectedLikeAnUnknownOne() throws Exception {
        UUID ada = newUser();
        catalog.skill("retired-skill", "Retired", false);

        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "skills", List.of("retired-skill")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skills[0]"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown or inactive skill 'retired-skill'."));
    }

    @Test
    void aSkillSentTwiceIsA400OnTheRepeatEvenWhenTheCatalogKnowsIt() throws Exception {
        UUID ada = newUser();

        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "skills", List.of("java", "spring-boot", "java")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("skills[2]"))
                .andExpect(jsonPath("$.errors[0].message").value("Skill 'java' is listed more than once."));
        assertThat(configurationCount(ada)).isZero();
    }

    @Test
    void sixSkillsAreAcceptedAndSevenAreNot() throws Exception {
        UUID ada = newUser();
        List<String> six = List.of("java", "spring-boot", "postgresql", "system-design", "react", "typescript");

        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "skills", six)).andExpect(status().isCreated()).andExpect(jsonPath("$.skills", hasSize(6)));
        catalog.skill("extra", "Extra", true);
        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "skills", List.of("java", "spring-boot", "postgresql", "system-design", "react", "typescript", "extra")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skills"));
        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "skills", List.of())).andExpect(status().isBadRequest());
        assertThat(configurationCount(ada)).isEqualTo(1);
    }

    @Test
    void theCodingLanguageRulesFollowTheInterviewType() throws Exception {
        UUID ada = newUser();

        postAs(ada, URL, with(FULL_LOOP_CONFIGURATION, "codingLanguage", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("codingLanguage"))
                .andExpect(jsonPath("$.errors[0].message").value("Required for a FULL_LOOP interview."));
        postAs(ada, URL, with(FULL_LOOP_CONFIGURATION, "codingLanguage", "rust"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Must be one of [java, javascript, typescript, python, go]."));
        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "codingLanguage", "java"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Not allowed for a TECHNICAL interview."));
        for (String type : List.of("CODING", "FULL_LOOP")) {
            postAs(ada, URL, with(with(FULL_LOOP_CONFIGURATION, "interviewType", type), "codingLanguage", "go"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.codingLanguage").value("go"));
        }
        postAs(ada, URL, with(with(TECHNICAL_CONFIGURATION, "interviewType", "SYSTEM_DESIGN"), "codingLanguage", "java"))
                .andExpect(status().isBadRequest());
        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "interviewType", "SYSTEM_DESIGN")).andExpect(status().isCreated());
        assertThat(configurationCount(ada)).isEqualTo(3);
    }

    @Test
    void aPersonalisedConfigurationCanBeSavedBeforeAnyResumeIsUploaded() throws Exception {
        UUID ada = newUser();

        // Whether a résumé exists is checked when an interview is created from the configuration (ResumeInterviewIT).
        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "useResume", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.useResume").value(true));
        assertThat(configurationCount(ada)).isEqualTo(1);
    }

    @Test
    void everyViolationIsReportedInOneAnswer() throws Exception {
        UUID ada = newUser();
        String broken = with(with(with(FULL_LOOP_CONFIGURATION, "durationMinutes", 20), "skills", List.of("java", "cobol", "java")),
                "codingLanguage", null);

        postAs(ada, URL, with(broken, "useResume", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[*].field", contains(
                        "codingLanguage", "durationMinutes", "skills[1]", "skills[2]")));
    }

    @Test
    void annotationViolationsComeFromTheStackToo() throws Exception {
        postAs(newUser(), URL, "{\"role\":\"BACKEND\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", contains("difficulty", "durationMinutes", "experienceLevel", "interviewType", "skills")));
    }

    @Test
    void theOwnerIsTheCallerWhateverTheBodySays() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();

        postAs(ada, URL, with(TECHNICAL_CONFIGURATION, "ownerUserId", grace.toString())).andExpect(status().isBadRequest());
        postAs(ada, URL, TECHNICAL_CONFIGURATION).andExpect(status().isCreated());

        assertThat(configurationCount(ada)).isEqualTo(1);
        assertThat(configurationCount(grace)).isZero();
    }

    // ---- list and get -----------------------------------------------------------------------------------------

    @Test
    void theListIsPagedNewestFirstAndOnlyHoldsTheCallersConfigurations() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();
        List<UUID> adas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            adas.add(saveConfiguration(ada, with(TECHNICAL_CONFIGURATION, "durationMinutes", 15 + 15 * (i % 3))));
            clock.advance(Duration.ofMinutes(1));
            saveConfiguration(grace, TECHNICAL_CONFIGURATION);
        }

        getAs(ada, URL + "?page=0&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[*].id", contains(adas.get(4).toString(), adas.get(3).toString())));
        getAs(ada, URL + "?page=2&size=2")
                .andExpect(jsonPath("$.items[*].id", contains(adas.get(0).toString())));
        getAs(ada, URL).andExpect(jsonPath("$.items", hasSize(5))).andExpect(jsonPath("$.size").value(20));
        getAs(grace, URL).andExpect(jsonPath("$.totalItems").value(5));
        getAs(newUser(), URL).andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void oneConfigurationIsReadBackExactlyAsItWasCreated() throws Exception {
        UUID ada = newUser();
        JsonNode created = body(postAs(ada, URL, FULL_LOOP_CONFIGURATION));

        JsonNode read = body(getAs(ada, URL + "/" + created.get("id").asString()).andExpect(status().isOk()));

        assertThat(read).isEqualTo(created);
    }

    @Test
    void aSkillRetiredLaterIsStillShownOnTheConfigurationThatChoseIt() throws Exception {
        UUID ada = newUser();
        long skill = catalog.skill("soon-retired", "Soon Retired", true);
        UUID id = saveConfiguration(ada, with(TECHNICAL_CONFIGURATION, "skills", List.of("java", "soon-retired")));
        catalog.deactivate(skill);

        getAs(ada, URL + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[*].slug", contains("java", "soon-retired")));
        getAs(ada, URL).andExpect(jsonPath("$.items[0].skills[1].name").value("Soon Retired"));
    }

    // ---- delete -----------------------------------------------------------------------------------------------

    @Test
    void aConfigurationNobodyUsesIsDeletedWithItsSkills() throws Exception {
        UUID ada = newUser();
        UUID id = saveConfiguration(ada, TECHNICAL_CONFIGURATION);

        deleteAs(ada, URL + "/" + id).andExpect(status().isNoContent()).andExpect(content().string(""));

        getAs(ada, URL + "/" + id).andExpect(status().isNotFound());
        assertThat(configurationCount(ada)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM interview_configuration_skills WHERE configuration_id = :c").param("c", id)
                .query(Long.class).single()).isZero();
        deleteAs(ada, URL + "/" + id).andExpect(status().isNotFound());
    }

    @Test
    void aConfigurationAnInterviewWasCreatedFromCannotBeDeleted() throws Exception {
        UUID ada = newUser();
        UUID id = saveConfiguration(ada, TECHNICAL_CONFIGURATION);
        postAs(ada, "/interviews", "{\"configurationId\":\"" + id + "\"}").andExpect(status().isCreated());

        deleteAs(ada, URL + "/" + id)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONFIGURATION_IN_USE"));

        getAs(ada, URL + "/" + id).andExpect(status().isOk());
        assertThat(configurationCount(ada)).isEqualTo(1);
    }

    @Test
    void aConfigurationOfAnAbandonedInterviewIsStillInUse() throws Exception {
        UUID ada = newUser();
        UUID interview = createInterview(ada);
        postAs(ada, "/interviews/" + interview + "/abandon").andExpect(status().isOk());
        UUID configuration = configurations.findAll().stream()
                .filter(candidate -> candidate.getOwnerUserId().equals(ada)).findFirst().orElseThrow().getId();

        deleteAs(ada, URL + "/" + configuration).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_IN_USE"));
    }

    @Test
    void aConfigurationThatTheInterviewsOfAnotherConfigurationUseDoesNotBlockTheDeletionOfAFreeOne() throws Exception {
        UUID ada = newUser();
        UUID used = saveConfiguration(ada, TECHNICAL_CONFIGURATION);
        UUID free = saveConfiguration(ada, TECHNICAL_CONFIGURATION);
        postAs(ada, "/interviews", "{\"configurationId\":\"" + used + "\"}").andExpect(status().isCreated());

        deleteAs(ada, URL + "/" + free).andExpect(status().isNoContent());

        getAs(ada, URL + "/" + used).andExpect(status().isOk());
        assertThat(configurationCount(ada)).isEqualTo(1);
    }

    @Test
    void anInterviewOfSomebodyElseDoesNotBlockTheOwnersDeletionOfADifferentConfiguration() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();
        UUID adas = saveConfiguration(ada, TECHNICAL_CONFIGURATION);
        createInterview(grace);

        deleteAs(ada, URL + "/" + adas).andExpect(status().isNoContent());
    }

    @Test
    void thereAreNoRoutesToChangeAConfiguration() throws Exception {
        UUID ada = newUser();
        UUID id = saveConfiguration(ada, TECHNICAL_CONFIGURATION);

        mockMvc.perform(put(API + URL + "/" + id)
                        .with(as(ada)).contentType(MediaType.APPLICATION_JSON).content(FULL_LOOP_CONFIGURATION))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get(API + URL + "/" + id).with(as(ada))).andExpect(jsonPath("$.interviewType").value("TECHNICAL"));
    }

    @Test
    void anIdThatIsNotAUuidIsA400() throws Exception {
        getAs(newUser(), URL + "/not-a-uuid").andExpect(status().isBadRequest());
        deleteAs(newUser(), URL + "/123").andExpect(status().isBadRequest());
    }

    @Test
    void manyConfigurationsOfOneCallerKeepTheirOwnSkills() throws Exception {
        UUID ada = newUser();
        IntStream.range(0, 3).forEach(i -> {
            try {
                saveConfiguration(ada, with(TECHNICAL_CONFIGURATION, "skills", List.of(i == 0 ? "java" : i == 1 ? "react" : "postgresql")));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });

        getAs(ada, URL).andExpect(jsonPath("$.items[*].skills[0].slug", contains("postgresql", "react", "java")));
        assertThat(Set.copyOf(configurations.findAll().stream().filter(c -> c.getOwnerUserId().equals(ada)).map(c -> c.getSkillIds().size()).toList()))
                .containsExactly(1);
    }
}
