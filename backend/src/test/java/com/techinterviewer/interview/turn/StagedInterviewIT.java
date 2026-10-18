package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.port.QuestionRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A fundamentals-first interview through the HTTP API (AI_ARCHITECTURE.md section 2.15): the configuration keeps its strategy, the
 * questions are typed and asked stage by stage from the question bank, and the report is broken down by assessment area.
 */
class StagedInterviewIT extends TurnApiTestBase {

    private static final String FOUR_TECHNOLOGIES = """
            {"role":"FULL_STACK","experienceLevel":"MID","difficulty":"MEDIUM","durationMinutes":45,
             "interviewType":"TECHNICAL","skills":["java","spring-boot","react","typescript"]}""";

    @Test
    void theCatalogOffersTheStrategiesAndAConfigurationKeepsItsOwn() throws Exception {
        UUID ada = newUser();

        getAs(ada, "/catalog/options")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategies[0].value").value("FUNDAMENTALS_FIRST"))
                .andExpect(jsonPath("$.strategies[0].mix.fundamentals").value(60))
                .andExpect(jsonPath("$.strategies[2].value").value("RESUME_FOCUSED"))
                .andExpect(jsonPath("$.strategies[2].requiresResume").value(true))
                .andExpect(jsonPath("$.strategies[3].mix").isEmpty());

        UUID standard = saveConfiguration(ada, FOUR_TECHNOLOGIES);
        UUID custom = saveConfiguration(ada, with(with(FOUR_TECHNOLOGIES, "strategy", "CUSTOM"), "stageMix",
                Map.of("fundamentals", 30, "practical", 70, "project", 0)));

        getAs(ada, "/interview-configurations/" + standard)
                .andExpect(jsonPath("$.strategy").value("FUNDAMENTALS_FIRST"))
                .andExpect(jsonPath("$.stageMix.fundamentals").value(60))
                .andExpect(jsonPath("$.stageMix.project").value(15));
        getAs(ada, "/interview-configurations/" + custom)
                .andExpect(jsonPath("$.strategy").value("CUSTOM"))
                .andExpect(jsonPath("$.stageMix.practical").value(70));
    }

    @Test
    void anInvalidStrategyIsRefusedOnItsField() throws Exception {
        UUID ada = newUser();

        postAs(ada, "/interview-configurations", with(with(FOUR_TECHNOLOGIES, "strategy", "CUSTOM"), "stageMix",
                Map.of("fundamentals", 30, "practical", 30, "project", 30)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("stageMix"));
        postAs(ada, "/interview-configurations", with(FOUR_TECHNOLOGIES, "strategy", "RESUME_FOCUSED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("strategy"));
        postAs(ada, "/interview-configurations", with(FOUR_TECHNOLOGIES, "strategy", "EASY_MODE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyTechnologysFundamentalsAreAskedFirstFromTheQuestionBankAndTheReportShowsTheAreas() throws Exception {
        UUID ada = newUser();
        UUID interview = startedInterview(ada, FOUR_TECHNOLOGIES);

        playAll(ada, interview);

        List<QuestionRequest> technical = generator.requests().stream().filter(request -> request.questionType() != null).toList();
        assertThat(technical).hasSizeGreaterThanOrEqualTo(4);
        assertThat(technical.subList(0, 4)).allSatisfy(request -> {
            assertThat(request.questionType().stage()).isEqualTo(InterviewStage.FUNDAMENTALS);
            assertThat(request.template()).as("a curated angle for " + request.topic().key()).isNotNull();
            assertThat(request.candidateClaims()).isEmpty();
        }).extracting(request -> request.topic().skillSlug()).containsExactlyInAnyOrder("java", "spring-boot", "react", "typescript");
        assertThat(technical).extracting(QuestionRequest::template).filteredOn(Objects::nonNull)
                .extracting(template -> template.id()).doesNotHaveDuplicates();

        List<Map<String, Object>> rows = jdbc.sql("SELECT kind, question_type, template_id FROM questions WHERE interview_id = :id "
                + "AND section = 'QUESTIONING' ORDER BY sequence_no").param("id", interview).query().listOfRows();
        assertThat(rows).isNotEmpty().allSatisfy(row -> assertThat(row.get("question_type")).isNotNull());
        assertThat(rows).filteredOn(row -> "TECHNICAL".equals(row.get("kind")) && !QuestionType.EXPERIENCE.name().equals(row.get("question_type")))
                .allSatisfy(row -> assertThat(row.get("template_id")).isNotNull());
        JsonNode shown = body(getAs(ada, "/interviews/" + interview + "/transcript")).get("entries").get(1).get("question");
        assertThat(shown.get("questionType").asString()).isIn("CONCEPT", "INTERNALS");

        finish(ada, interview, "CANDIDATE_ENDED");
        JsonNode report = body(getAs(ada, "/interviews/" + interview + "/report").andExpect(status().isOk()));
        JsonNode areas = report.get("assessmentAreas");
        assertThat(areas.get("areas")).extracting(area -> area.get("area").asString())
                .containsExactly("FUNDAMENTALS", "PRACTICAL", "PROBLEM_SOLVING", "PROJECT", "COMMUNICATION");
        JsonNode fundamentals = areas.get("areas").get(0);
        assertThat(fundamentals.get("planned").asBoolean()).isTrue();
        assertThat(fundamentals.get("evidenceCount").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(fundamentals.get("shareOfOverall").asDouble()).isPositive();
        assertThat(areas.get("skillsNotAssessed")).isEmpty();
        assertThat(areas.get("areas").get(4).get("shareOfOverall").isNull()).isTrue();
    }
}
