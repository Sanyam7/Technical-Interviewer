package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The generated API documentation lists every operation of the interview API with its bearer requirement and its problems. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class InterviewOpenApiIT {

    private static final String BASE = "/api/v1";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper json;

    private JsonNode documentation() throws Exception {
        return json.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "post,/interview-configurations,201",
            "get,/interview-configurations,200",
            "get,/interview-configurations/{id},200",
            "delete,/interview-configurations/{id},204",
            "post,/interviews,201",
            "get,/interviews,200",
            "get,/interviews/{id},200",
            "get,/interviews/{id}/state,200",
            "post,/interviews/{id}/start,200",
            "get,/interviews/{id}/questions,200",
            "get,/interviews/{id}/questions/current,200",
            "get,/interviews/{id}/questions/{questionId},200",
            "post,/interviews/{id}/abandon,200",
            "get,/interviews/{id}/transcript,200",
            "post,/interviews/{id}/answers,200",
            "post,/interviews/{id}/questions/{questionId}/skip,200",
            "post,/interviews/{id}/questions/next,200",
            "post,/interviews/{id}/finish,200"})
    void everyOperationIsDocumentedWithItsSuccessAndTheBearerRequirement(String method, String path, String success) throws Exception {
        String operation = "$.paths['" + BASE + path + "']." + method;

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".operationId").isNotEmpty())
                .andExpect(jsonPath(operation + ".summary").isNotEmpty())
                .andExpect(jsonPath(operation + ".responses['" + success + "']").exists())
                .andExpect(jsonPath(operation + ".responses['401'].content['application/problem+json']").exists())
                .andExpect(jsonPath(operation + ".security").doesNotExist())
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "get,/interview-configurations/{id}",
            "delete,/interview-configurations/{id}",
            "get,/interviews/{id}",
            "get,/interviews/{id}/state",
            "post,/interviews/{id}/start",
            "get,/interviews/{id}/questions",
            "get,/interviews/{id}/questions/current",
            "get,/interviews/{id}/questions/{questionId}",
            "post,/interviews/{id}/abandon",
            "get,/interviews/{id}/transcript",
            "post,/interviews/{id}/answers",
            "post,/interviews/{id}/questions/{questionId}/skip",
            "post,/interviews/{id}/questions/next",
            "post,/interviews/{id}/finish"})
    void operationsOnOneResourceDocumentTheNotFoundProblemAndTheirPathParameters(String method, String path) throws Exception {
        String operation = "$.paths['" + BASE + path + "']." + method;

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(operation + ".responses['404'].content['application/problem+json']").exists())
                .andExpect(jsonPath(operation + ".responses['400'].content['application/problem+json']").exists())
                .andExpect(jsonPath(operation + ".parameters[?(@.in=='path')].name").value(hasItems("id")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"/interviews/{id}/start", "/interviews/{id}/abandon", "/interviews/{id}/answers",
            "/interviews/{id}/questions/{questionId}/skip", "/interviews/{id}/questions/next", "/interviews/{id}/finish"})
    void theTransitionsDocumentTheIllegalStateConflict(String path) throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['" + BASE + path + "'].post.responses['409'].content['application/problem+json']").exists());
    }

    @Test
    void creatingAnInterviewDocumentsItsLocationItsBodyAndTheDailyLimit() throws Exception {
        String operation = "$.paths['" + BASE + "/interviews'].post";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(operation + ".requestBody.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/CreateInterviewRequest"))
                .andExpect(jsonPath(operation + ".responses['201'].headers.Location").exists())
                .andExpect(jsonPath(operation + ".responses['201'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/InterviewResponse"))
                .andExpect(jsonPath("$.components.schemas.CreateInterviewRequest.example.configuration.interviewType").value("FULL_LOOP"))
                .andExpect(jsonPath(operation + ".responses['429'].headers['Retry-After']").exists())
                .andExpect(jsonPath(operation + ".responses['404'].content['application/problem+json']").exists());
    }

    @Test
    void theTurnEndpointsDocumentTheirConflictsTheRateLimitAndTheUnavailableAi() throws Exception {
        String answers = "$.paths['" + BASE + "/interviews/{id}/answers'].post";
        String next = "$.paths['" + BASE + "/interviews/{id}/questions/next'].post";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(answers + ".requestBody.content['application/json'].schema['$ref']").value("#/components/schemas/AnswerRequest"))
                .andExpect(jsonPath(answers + ".responses['200'].content['application/json'].schema['$ref']").value("#/components/schemas/AnswerResponse"))
                .andExpect(jsonPath(answers + ".responses['409'].content['application/problem+json'].examples").exists())
                .andExpect(jsonPath(answers + ".responses['429'].headers['Retry-After']").exists())
                .andExpect(jsonPath(answers + ".responses['400'].content['application/problem+json'].examples").exists())
                .andExpect(jsonPath(next + ".responses['503'].content['application/problem+json']").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/interviews/{id}/finish'].post.responses['200'].content['application/json'].examples").exists());
    }

    @Test
    void theTurnSchemasCarryExactlyTheFieldsOfTheContractAndNoServerSideOne() throws Exception {
        JsonNode schemas = documentation().get("components").get("schemas");

        assertThat(schemas.get("AnswerRequest").get("properties").propertyNames())
                .containsExactlyInAnyOrder("questionId", "content", "codeLanguage", "timeTakenSeconds", "architecture");
        assertThat(schemas.get("AnswerResponse").get("properties").propertyNames())
                .containsExactlyInAnyOrder("accepted", "state", "progress", "nextQuestion", "awaitingNextQuestion", "interviewComplete");
        assertThat(schemas.get("FinishRequest").get("properties").propertyNames()).containsExactly("reason");
        assertThat(schemas.get("FinishRequest").get("properties").get("reason").toString()).contains("CANDIDATE_ENDED").contains("ACKNOWLEDGE_COMPLETE");
        assertThat(schemas.get("FinishResponse").get("properties").propertyNames())
                .containsExactlyInAnyOrder("state", "reportStatus", "retryable", "reportUrl");
        assertThat(schemas.get("SkipRequest").get("properties").propertyNames()).containsExactly("timeTakenSeconds");
        for (String name : List.of("AnswerRequest", "AnswerResponse", "FinishRequest", "FinishResponse", "SkipRequest")) {
            assertThat(schemas.get(name).get("description").asString()).as(name + " description").isNotBlank();
        }
    }

    @Test
    void theListsDocumentTheirPagingAndTheStateFilter() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['" + BASE + "/interviews'].get.parameters[?(@.name=='state')].in").value(hasItems("query")))
                .andExpect(jsonPath("$.paths['" + BASE + "/interviews'].get.parameters[?(@.name=='state')].schema.enum[*]")
                        .value(hasItems("INIT", "QUESTIONING", "COMPLETED", "ABANDONED")))
                .andExpect(jsonPath("$.paths['" + BASE + "/interviews'].get.parameters[?(@.name=='size')].schema.maximum").value(hasItems(100)))
                .andExpect(jsonPath("$.paths['" + BASE + "/interview-configurations'].get.parameters[?(@.name=='page')].schema.minimum")
                        .value(hasItems(0)));
    }

    @Test
    void theSchemasDescribeTheirFieldsAndNeverTheServerSideOnes() throws Exception {
        JsonNode docs = documentation();
        JsonNode schemas = docs.get("components").get("schemas");

        for (String name : List.of("SessionResponse", "StateResponse", "QuestionResponse", "ProgressResponse", "TranscriptResponse",
                "TranscriptEntry", "FeedbackResponse", "InterviewResponse", "InterviewSummaryResponse", "PublicPlan", "PublicSectionPlan",
                "ConfigurationResponse", "ConfigurationRequest", "CreateInterviewRequest")) {
            assertThat(schemas.has(name)).as(name).isTrue();
            assertThat(schemas.get(name).get("description").asString()).as(name + " description").isNotBlank();
        }
        assertThat(schemas.get("QuestionResponse").get("properties").propertyNames())
                .containsExactlyInAnyOrder("id", "sequenceNo", "kind", "isFollowUp", "section", "topic", "prompt", "answerFormat",
                        "suggestedMinutes", "details", "answered", "design", "resume", "questionType");
        assertThat(schemas.get("SessionResponse").get("properties").propertyNames())
                .containsExactlyInAnyOrder("id", "state", "serverTime", "startedAt", "deadlineAt", "endReason", "progress",
                        "currentQuestion", "awaitingNextQuestion", "interviewComplete", "version", "configuration");
        assertThat(schemas.get("PublicSectionPlan").get("properties").propertyNames())
                .containsExactlyInAnyOrder("section", "minutes", "plannedQuestions");
        assertThat(schemas.get("ConfigurationRequest").get("properties").get("durationMinutes").get("example").asInt()).isEqualTo(45);
        assertThat(schemas.get("QuestionResponse").get("properties").get("answerFormat").toString()).contains("CODE").contains("TEXT");
    }
}
