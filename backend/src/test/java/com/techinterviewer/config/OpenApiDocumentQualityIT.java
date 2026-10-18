package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.techinterviewer.TestJson;
import com.techinterviewer.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The generated OpenAPI document must be one a client generator can use: every reference resolves, nullability is
 * expressed in a way that can be satisfied, enums match their type, headers and errors are fully described, and the
 * required properties of the responses are listed. (The document is also valid against the OpenAPI 3.1 schema; that
 * was checked once with two external validators, this test keeps the properties that broke before.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class OpenApiDocumentQualityIT {

    @Autowired
    MockMvc mockMvc;

    JsonNode doc;

    @BeforeEach
    void load() throws Exception {
        doc = TestJson.applicationMapper().readTree(
                mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
    }

    private JsonNode schema(String name) {
        JsonNode schema = doc.path("components").path("schemas").path(name);
        assertThat(schema.isMissingNode()).as("schema " + name).isFalse();
        return schema;
    }

    private static boolean allowsNull(JsonNode property) {
        JsonNode type = property.path("type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if ("null".equals(t.asString())) {
                    return true;
                }
            }
        }
        for (JsonNode member : property.path("oneOf")) {
            if ("null".equals(member.path("type").asString())) {
                return true;
            }
        }
        return false;
    }

    private void walk(JsonNode node, java.util.function.Consumer<JsonNode> visitor) {
        visitor.accept(node);
        if (node.isObject()) {
            node.properties().forEach(entry -> walk(entry.getValue(), visitor));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, visitor));
        }
    }

    @Test
    void everyReferenceResolvesToADefinedSchema() {
        Set<String> defined = new HashSet<>();
        doc.path("components").path("schemas").propertyNames().forEach(defined::add);
        List<String> broken = new ArrayList<>();

        walk(doc, node -> {
            String ref = node.path("$ref").asString("");
            if (ref.startsWith("#/components/schemas/") && !defined.contains(ref.substring("#/components/schemas/".length()))) {
                broken.add(ref);
            }
        });

        assertThat(broken).isEmpty();
    }

    @Test
    void noPropertyIsBothANullTypeAndAReference() {
        List<String> offenders = new ArrayList<>();

        doc.path("components").path("schemas").properties().forEach(schema ->
                schema.getValue().path("properties").properties().forEach(property -> {
                    JsonNode value = property.getValue();
                    boolean referenceWithType = value.has("$ref") && value.has("type");
                    if (referenceWithType) {
                        offenders.add(schema.getKey() + "." + property.getKey());
                    }
                }));

        assertThat(offenders).as("a $ref next to a type is unsatisfiable when the type is null").isEmpty();
    }

    @Test
    void nullableObjectsAreOneOfTheObjectAndNull() {
        JsonNode currentQuestion = schema("SessionResponse").path("properties").path("currentQuestion");

        assertThat(currentQuestion.path("oneOf")).hasSize(2);
        assertThat(currentQuestion.path("oneOf").get(0).path("$ref").asString()).endsWith("/QuestionResponse");
        assertThat(currentQuestion.path("oneOf").get(1).path("type").asString()).isEqualTo("null");
    }

    @Test
    void nullableEnumsAndScalarsListNullAmongTheirTypes() {
        JsonNode endReason = schema("SessionResponse").path("properties").path("endReason");

        assertThat(allowsNull(endReason)).isTrue();
        assertThat(endReason.path("enum").toString()).contains("null");
        assertThat(allowsNull(schema("ReportResponse").path("properties").path("overallScore"))).isTrue();
        assertThat(allowsNull(schema("SessionResponse").path("properties").path("startedAt"))).isTrue();
        assertThat(allowsNull(schema("SessionResponse").path("properties").path("state"))).as("state is never null").isFalse();
    }

    @Test
    void responseSchemasRequireEveryPropertyThatCanNeverBeNull() {
        List<String> required = new ArrayList<>();
        schema("SessionResponse").path("required").forEach(name -> required.add(name.asString()));

        assertThat(required).contains("id", "state", "serverTime", "progress", "awaitingNextQuestion", "interviewComplete",
                "version", "configuration");
        assertThat(required).doesNotContain("currentQuestion", "endReason", "startedAt", "deadlineAt");
        assertThat(schema("TokenResponse").path("required").toString()).contains("accessToken", "expiresIn", "user");
        assertThat(schema("ReportResponse").path("required").toString()).contains("interviewId", "status", "retryable");
        assertThat(schema("ReportResponse").path("required").toString()).doesNotContain("overallScore");
    }

    @Test
    void integerEnumsListIntegers() {
        JsonNode duration = schema("ConfigurationRequest").path("properties").path("durationMinutes");

        assertThat(duration.path("enum").toString()).isEqualTo("[15,30,45,60,90]");
    }

    @Test
    void everyResponseHeaderDeclaresItsSchema() {
        List<String> missing = new ArrayList<>();

        doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(operation ->
                operation.getValue().path("responses").properties().forEach(response ->
                        response.getValue().path("headers").properties().forEach(header -> {
                            if (!header.getValue().has("schema") && !header.getValue().has("content")) {
                                missing.add(path.getKey() + " " + operation.getKey() + " " + response.getKey() + " " + header.getKey());
                            }
                        }))));

        assertThat(missing).isEmpty();
    }

    @Test
    void everyErrorResponseIsTheProblemDocument() {
        List<String> offenders = new ArrayList<>();

        doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(operation ->
                operation.getValue().path("responses").properties().forEach(response -> {
                    boolean error = response.getKey().startsWith("4") || response.getKey().startsWith("5");
                    JsonNode content = response.getValue().path("content");
                    if (error && !(content.size() == 1
                            && content.path("application/problem+json").path("schema").path("$ref").asString().endsWith("/Problem"))) {
                        offenders.add(path.getKey() + " " + operation.getKey() + " " + response.getKey());
                    }
                })));

        assertThat(offenders).isEmpty();
    }

    @Test
    void stateChangingOperationsDocumentTheRateLimitAndRequestOperationsTheirLimits() {
        assertThat(doc.at("/paths/~1api~1v1~1interviews/post/responses/429/headers/Retry-After/schema/type").asString()).isEqualTo("integer");
        assertThat(doc.at("/paths/~1api~1v1~1interviews~1{id}~1answers/post/responses").has("429")).isTrue();
        assertThat(doc.at("/paths/~1api~1v1~1profile/put/responses").has("429")).isTrue();
        assertThat(doc.at("/paths/~1api~1v1~1interview-configurations~1{id}/delete/responses").has("429")).isTrue();
        assertThat(doc.at("/paths/~1api~1v1~1auth~1logout/post/responses").has("429")).as("logout is not limited").isFalse();
        assertThat(doc.at("/paths/~1api~1v1~1profile/get/responses").has("429")).as("reads are not limited").isFalse();
        for (String body : List.of("/paths/~1api~1v1~1interviews~1{id}~1answers/post/responses",
                "/paths/~1api~1v1~1auth~1login/post/responses", "/paths/~1api~1v1~1profile/put/responses")) {
            assertThat(doc.at(body).has("400")).as(body).isTrue();
            assertThat(doc.at(body).has("413")).as(body).isTrue();
            assertThat(doc.at(body).has("415")).as(body).isTrue();
        }
    }

    @Test
    void theProblemDocumentDescribesCurrentStateAndRequiresItsStandardMembers() {
        JsonNode problem = schema("Problem");

        assertThat(problem.path("properties").path("currentState").path("enum").toString()).contains("QUESTIONING", "FINAL_EVALUATION");
        assertThat(problem.path("required").toString()).contains("detail", "instance", "traceId", "code");
        assertThat(problem.path("properties").path("code").path("enum").toString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void authenticationEndpointsAnswerJsonAndTheContainerErrorEndpointIsHidden() {
        assertThat(doc.at("/paths/~1api~1v1~1auth~1login/post/responses/200/content").has("application/json")).isTrue();
        assertThat(doc.at("/paths/~1api~1v1~1auth~1login/post/responses/200/content").has("*/*")).isFalse();
        assertThat(doc.at("/paths/~1api~1v1~1users~1me/get/responses/200/content").has("application/json")).isTrue();
        assertThat(doc.path("paths").has("/error")).isFalse();
    }
}
