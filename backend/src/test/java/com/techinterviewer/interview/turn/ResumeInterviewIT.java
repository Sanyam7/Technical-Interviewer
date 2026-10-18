package com.techinterviewer.interview.turn;

import static com.techinterviewer.resume.ResumeFiles.RESUME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.ResumeAnchor;
import com.techinterviewer.resume.ResumeFiles;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * A personalised interview end to end (AI_ARCHITECTURE.md section 2.13): résumé → verified profile → candidate context → plan →
 * anchored questions, with the excerpt shown to the candidate, each experience used once, and personalisation ending when the
 * résumé is deleted.
 */
class ResumeInterviewIT extends TurnApiTestBase {

    private static final String PERSONALISED = """
            {"role":"BACKEND","experienceLevel":"SENIOR","difficulty":"MEDIUM","durationMinutes":45,
             "interviewType":"TECHNICAL","skills":["spring-boot","system-design"],"useResume":true}""";

    /** A personalised interview whose technical questions are all about the candidate's own work (AI_ARCHITECTURE.md section 2.15). */
    private static final String PROJECTS_ONLY = PERSONALISED.replace("\"useResume\":true}",
            "\"useResume\":true,\"strategy\":\"CUSTOM\",\"stageMix\":{\"fundamentals\":0,\"practical\":0,\"project\":100}}");

    private static final Set<String> STATEMENTS = Set.of(
            "Built a Spring Boot microservice using Kafka to process card payment events.",
            "Reduced p99 latency of the checkout API by 40% with Redis caching.",
            "Led the migration of the ledger from MySQL to PostgreSQL with zero downtime.",
            "Designed REST APIs for the order service in Java and Spring Boot.",
            "Wrote integration tests with Testcontainers and JUnit.");

    private void uploadResume(UUID user) throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, API + "/profile/resume")
                        .file(new MockMultipartFile("file", "cv.pdf", "application/pdf", ResumeFiles.pdf(RESUME)))
                        .with(as(user)))
                .andExpect(status().isOk());
    }

    private UUID resumeIdOf(UUID interview) {
        return jdbc.sql("SELECT resume_id FROM interviews WHERE id = :id").param("id", interview).query(UUID.class).optional().orElse(null);
    }

    private List<ResumeAnchor> anchorsRequested() {
        return generator.requests().stream().map(QuestionRequest::resumeAnchor).filter(Objects::nonNull).toList();
    }

    @Test
    void aPersonalisedInterviewNeedsAResume() throws Exception {
        UUID ada = newUser();

        postAs(ada, "/interviews", "{\"configuration\":" + PERSONALISED + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("configuration.useResume"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.startsWith("This interview is personalised from your résumé")));

        UUID saved = saveConfiguration(ada, PERSONALISED);
        postAs(ada, "/interviews", "{\"configurationId\":\"" + saved + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("configurationId"));
    }

    @Test
    void theDefaultInterviewUsesTheResumeOnlyInItsProjectStageAfterTheFundamentals() throws Exception {
        UUID ada = newUser();
        uploadResume(ada);
        UUID interview = startedInterview(ada, PERSONALISED);

        assertThat(resumeIdOf(interview)).isNotNull();
        JsonNode plan = planOf(interview);
        int projectSlots = 0;
        for (JsonNode section : plan.get("sections")) {
            if (section.get("section").asString().equals("QUESTIONING")) {
                for (JsonNode stage : section.get("stages")) {
                    if (stage.get("stage").asString().equals("PROJECT")) {
                        projectSlots = stage.get("slots").asInt();
                    }
                }
            }
        }
        JsonNode resumePlan = plan.get("resume");
        assertThat(resumePlan.get("questions").asInt()).isEqualTo(projectSlots).isPositive();
        // The plan holds ids and topics, never the résumé's words.
        assertThat(resumePlan.toString()).doesNotContain("Kafka", "Spring Boot");

        answerIntroduction(ada, interview);
        assertThat(generator.lastRequest().resumeAnchor()).as("the first question is about the technology").isNull();
        assertThat(generator.lastRequest().candidateClaims()).isEmpty();

        playAll(ada, interview);

        List<Map<String, Object>> rows = jdbc.sql("SELECT sequence_no, question_type, resume_item_id FROM questions WHERE interview_id = :id "
                + "AND kind = 'TECHNICAL' ORDER BY sequence_no").param("id", interview).query().listOfRows();
        List<ResumeAnchor> anchors = anchorsRequested();
        assertThat(rows).filteredOn(row -> row.get("resume_item_id") != null)
                .hasSameSizeAs(anchors)
                .hasSizeLessThanOrEqualTo(projectSlots)
                .allSatisfy(row -> assertThat(row.get("question_type")).isEqualTo("EXPERIENCE"));
        int firstAnchored = rows.stream().filter(row -> row.get("resume_item_id") != null)
                .mapToInt(row -> ((Number) row.get("sequence_no")).intValue()).min().orElse(Integer.MAX_VALUE);
        assertThat(rows).filteredOn(row -> List.of("CONCEPT", "INTERNALS").contains(row.get("question_type")))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(((Number) row.get("sequence_no")).intValue()).isLessThan(firstAnchored));
        long explained = jdbc.sql("SELECT count(*) FROM interview_state_transitions WHERE interview_id = :id AND details::text LIKE "
                + "'%anchored on résumé experience%'").param("id", interview).query(Long.class).single();
        assertThat(explained).isEqualTo(anchors.size());
    }

    @Test
    void aProjectInterviewAnchorsItsQuestionsOnTheResumeEachExperienceOnce() throws Exception {
        UUID ada = newUser();
        uploadResume(ada);
        UUID interview = startedInterview(ada, PROJECTS_ONLY);
        JsonNode resumePlan = planOf(interview).get("resume");
        assertThat(resumePlan.at("/topics").propertyNames()).contains("system-design/messaging-async", "system-design/caching");

        answerIntroduction(ada, interview);
        QuestionRequest first = generator.lastRequest();
        assertThat(first.resumeAnchor()).isNotNull();
        assertThat(first.questionType()).isEqualTo(com.techinterviewer.interview.domain.QuestionType.EXPERIENCE);
        assertThat(STATEMENTS).contains(first.resumeAnchor().text());
        JsonNode shown = currentQuestion(ada, interview);
        assertThat(shown.at("/resume/text").asString()).isEqualTo(first.resumeAnchor().text());
        assertThat(shown.get("questionType").asString()).isEqualTo("EXPERIENCE");

        playAll(ada, interview);

        List<ResumeAnchor> anchors = anchorsRequested();
        List<String> stored = jdbc.sql("SELECT resume_item_id FROM questions WHERE interview_id = :id AND resume_item_id IS NOT NULL "
                + "ORDER BY sequence_no").param("id", interview).query(String.class).list();
        assertThat(stored).hasSameSizeAs(anchors).doesNotHaveDuplicates();
        assertThat(stored.size()).isLessThanOrEqualTo(resumePlan.get("questions").asInt());
        assertThat(stored).isEqualTo(anchors.stream().map(ResumeAnchor::itemId).toList());
    }

    @Test
    void anAnchoredQuestionIsATopicQuestionLikeAnyOther() throws Exception {
        UUID ada = newUser();
        uploadResume(ada);
        UUID interview = startedInterview(ada, PROJECTS_ONLY);
        answerIntroduction(ada, interview);

        QuestionRequest request = generator.lastRequest();
        List<Map<String, Object>> rows = questionRows(interview);
        Map<String, Object> asked = rows.getLast();

        assertThat(asked.get("kind")).isEqualTo("TECHNICAL");
        assertThat(asked.get("topic")).isEqualTo(request.topic().key());
        assertThat(asked.get("rubric_id")).isEqualTo("technical.v2");
    }

    @Test
    void deletingTheResumeEndsThePersonalisationOfARunningInterview() throws Exception {
        UUID ada = newUser();
        uploadResume(ada);
        UUID interview = startedInterview(ada, PERSONALISED);
        answerIntroduction(ada, interview);
        assertThat(currentQuestion(ada, interview).has("resume")).isTrue();

        deleteAs(ada, "/profile/resume").andExpect(status().isNoContent());

        assertThat(resumeIdOf(interview)).isNull();
        JsonNode current = currentQuestion(ada, interview);
        assertThat(current.get("resume") == null || current.get("resume").isNull()).isTrue();
        int before = anchorsRequested().size();
        answerCurrent(ada, interview, "An answer that covers the main points.");
        answerCurrent(ada, interview, "Another answer.");
        assertThat(anchorsRequested()).hasSize(before);
    }

    @Test
    void anInterviewThatIsNotPersonalisedNeverReadsTheResume() throws Exception {
        UUID ada = newUser();
        uploadResume(ada);
        UUID interview = startedInterview(ada, PERSONALISED.replace("\"useResume\":true", "\"useResume\":false"));

        playAll(ada, interview);

        assertThat(resumeIdOf(interview)).isNull();
        assertThat(planOf(interview).has("resume")).isFalse();
        assertThat(anchorsRequested()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM questions WHERE interview_id = :id AND resume_item_id IS NOT NULL")
                .param("id", interview).query(Long.class).single()).isZero();
    }
}
