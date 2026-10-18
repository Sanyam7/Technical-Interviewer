package com.techinterviewer.ai.structured;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.ai.agents.output.AssessmentDraft;
import com.techinterviewer.ai.agents.output.DesignProbeDraft;
import com.techinterviewer.ai.agents.output.CodingDraft;
import com.techinterviewer.ai.agents.output.FollowUpDraft;
import com.techinterviewer.ai.agents.output.KeyPointDraft;
import com.techinterviewer.ai.agents.output.NarrativeDraft;
import com.techinterviewer.ai.agents.output.QuestionDraft;
import com.techinterviewer.ai.agents.output.ResumeDraft;
import com.techinterviewer.ai.support.AiStack;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The schema files: the supported keyword subset only, and in step with the records the output is bound to. */
class JsonSchemaRegistryTest {

    /** Keywords both Gemini and Ollama understand (AI_ARCHITECTURE.md section 5). */
    private static final Set<String> SUPPORTED = Set.of(
            "type", "description", "properties", "required", "additionalProperties", "items", "enum",
            "minimum", "maximum", "minItems", "maxItems");

    private final JsonSchemaRegistry schemas = AiStack.schemas();

    private static final String[] ALL = {"question.v1", "coding-question.v1", "follow-up-question.v1", "answer-evaluation.v1",
        "criterion-rating.v1", "report-narrative.v1", "question.v2", "coding-question.v2", "follow-up-question.v2",
        "answer-evaluation.v2", "report-narrative.v2", "answer-evaluation.v3", "answer-evaluation-coding.v1",
        "answer-evaluation-system-design.v1", "design-criterion-rating.v1", "design-probe.v1", "resume-extraction.v1", "resume-question.v1"};

    @Test
    void schemasUseOnlyKeywordsEveryProviderSupports() {
        for (String name : ALL) {
            Set<String> used = new TreeSet<>();
            collectKeywords(schemas.get(name), used);
            assertThat(SUPPORTED).as(name).containsAll(used);
        }
    }

    @Test
    void everyObjectRequiresAllItsPropertiesAndAllowsNoOthers() {
        for (String name : ALL) {
            assertStrictObjects(name, schemas.get(name));
        }
    }

    @Test
    void theLatestSchemasMatchTheRecordsTheOutputIsBoundTo() {
        assertMatches(schemas.get("question.v2"), QuestionDraft.class, Set.of("coding", "resumeQuote"));
        assertMatches(schemas.get("coding-question.v2"), QuestionDraft.class, Set.of("resumeQuote"));
        assertMatches((ObjectNode) schemas.get("coding-question.v2").at("/properties/coding"), CodingDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("question.v2").at("/properties/expectedKeyPoints/items"), KeyPointDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("coding-question.v2").at("/properties/expectedKeyPoints/items"), KeyPointDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("follow-up-question.v2").at("/properties/expectedKeyPoints/items"), KeyPointDraft.class, Set.of());
        assertMatches(schemas.get("follow-up-question.v2"), FollowUpDraft.class, Set.of());
        assertMatches(schemas.get("answer-evaluation.v3"), AssessmentDraft.class, Set.of("codeAnalysis"));
        assertMatches(schemas.get("answer-evaluation-coding.v1"), AssessmentDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("answer-evaluation-coding.v1").at("/properties/codeAnalysis"), AssessmentDraft.CodeAnalysisDraft.class,
                Set.of());
        assertMatches((ObjectNode) schemas.get("answer-evaluation-coding.v1").at("/properties/factualErrors/items"),
                AssessmentDraft.FactualErrorDraft.class, Set.of("criterion"));
        assertMatches((ObjectNode) schemas.get("answer-evaluation.v3").at("/properties/factualErrors/items"), AssessmentDraft.FactualErrorDraft.class,
                Set.of("criterion"));
        assertMatches(schemas.get("answer-evaluation-system-design.v1"), AssessmentDraft.class, Set.of("codeAnalysis"));
        assertMatches((ObjectNode) schemas.get("answer-evaluation-system-design.v1").at("/properties/factualErrors/items"),
                AssessmentDraft.FactualErrorDraft.class, Set.of());
        assertMatches(schemas.get("design-criterion-rating.v1"), AssessmentDraft.RatingDraft.class, Set.of());
        assertMatches(schemas.get("design-probe.v1"), DesignProbeDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("answer-evaluation.v3").at("/properties/confidence"), AssessmentDraft.ConfidenceDraft.class, Set.of());
        assertMatches(schemas.get("report-narrative.v2"), NarrativeDraft.class, Set.of());
        assertMatches(schemas.get("resume-question.v1"), QuestionDraft.class, Set.of("coding"));
        assertMatches(schemas.get("resume-extraction.v1"), ResumeDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("resume-extraction.v1").at("/properties/positions/items"), ResumeDraft.PositionDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("resume-extraction.v1").at("/properties/technologies/items"), ResumeDraft.FactDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("resume-extraction.v1").at("/properties/experiences/items"), ResumeDraft.ExperienceDraft.class,
                Set.of());
    }

    /**
     * Older versions still bind, the newer fields being absent: version 1 (before RAG) has no citations, versions before 3
     * of the answer evaluation neither confidence nor links to earlier mistakes.
     */
    @Test
    void schemasMatchTheRecordsTheOutputIsBoundTo() {
        Set<String> citations = Set.of("sources");
        assertMatches(schemas.get("answer-evaluation.v2"), AssessmentDraft.class, Set.of("confidence", "codeAnalysis"));
        assertMatches((ObjectNode) schemas.get("answer-evaluation.v2").at("/properties/factualErrors/items"), AssessmentDraft.FactualErrorDraft.class,
                Set.of("repeats", "criterion"));
        assertMatches(schemas.get("question.v1"), QuestionDraft.class, Set.of("coding", "resumeQuote"));
        assertMatches(schemas.get("coding-question.v1"), QuestionDraft.class, Set.of("resumeQuote"));
        assertMatches((ObjectNode) schemas.get("coding-question.v1").at("/properties/coding"), CodingDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("coding-question.v1").at("/properties/coding/properties/examples/items"), CodingDraft.ExampleDraft.class, Set.of());
        assertMatches((ObjectNode) schemas.get("question.v1").at("/properties/expectedKeyPoints/items"), KeyPointDraft.class, citations);
        assertMatches(schemas.get("follow-up-question.v1"), FollowUpDraft.class, Set.of());
        assertMatches(schemas.get("answer-evaluation.v1"), AssessmentDraft.class, Set.of("confidence", "codeAnalysis"));
        assertMatches((ObjectNode) schemas.get("answer-evaluation.v1").at("/properties/keyPoints/items"), AssessmentDraft.KeyPointVerdict.class, Set.of());
        assertMatches((ObjectNode) schemas.get("answer-evaluation.v1").at("/properties/factualErrors/items"), AssessmentDraft.FactualErrorDraft.class,
                Set.of("sources", "repeats", "criterion"));
        assertMatches(schemas.get("criterion-rating.v1"), AssessmentDraft.RatingDraft.class, Set.of("evidence"));
        assertMatches(schemas.get("report-narrative.v1"), NarrativeDraft.class, Set.of("furtherReading"));
        assertMatches((ObjectNode) schemas.get("report-narrative.v1").at("/properties/strengths/items"), NarrativeDraft.Item.class, Set.of());
    }

    @Test
    void theModelFlagsAndKeyPointStatusesOfTheSchemaAreTheJavaEnums() {
        JsonNode flags = schemas.get("answer-evaluation.v1").at("/properties/flags/items/enum");
        assertThat(flags.valueStream().map(JsonNode::asString).toList())
                .containsExactly(Arrays.stream(AssessmentDraft.ModelFlag.values()).map(Enum::name).toArray(String[]::new));
        JsonNode statuses = schemas.get("answer-evaluation.v1").at("/properties/keyPoints/items/properties/status/enum");
        assertThat(statuses.valueStream().map(JsonNode::asString).toList())
                .containsExactly(Arrays.stream(com.techinterviewer.evaluation.domain.KeyPointStatus.values()).map(Enum::name).toArray(String[]::new));
        JsonNode confidence = schemas.get("answer-evaluation.v3").at("/properties/confidence/properties/level/enum");
        assertThat(confidence.valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(Arrays.stream(com.techinterviewer.evaluation.domain.Confidence.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void anAgentGetsAPrivateCopy() {
        ObjectNode copy = schemas.get("question.v1");
        copy.put("type", "changed");

        assertThat(schemas.get("question.v1").get("type").asString()).isEqualTo("object");
    }

    private static void collectKeywords(JsonNode schema, Set<String> used) {
        schema.propertyNames().forEach(used::add);
        schema.path("properties").forEach(property -> collectKeywords(property, used));
        if (schema.path("items").isObject()) {
            collectKeywords(schema.get("items"), used);
        }
    }

    private static void assertStrictObjects(String where, JsonNode schema) {
        if (schema.path("type").asString("").equals("object")) {
            Set<String> properties = new HashSet<>(schema.path("properties").propertyNames());
            Set<String> required = new HashSet<>(schema.path("required").valueStream().map(JsonNode::asString).toList());
            assertThat(required).as(where + " requires every property").isEqualTo(properties);
            assertThat(schema.path("additionalProperties").asBoolean(true)).as(where + " allows no other property").isFalse();
        }
        schema.path("properties").properties().forEach(entry -> assertStrictObjects(where + "." + entry.getKey(), entry.getValue()));
        if (schema.path("items").isObject()) {
            assertStrictObjects(where + "[]", schema.get("items"));
        }
    }

    private static void assertMatches(ObjectNode schema, Class<? extends Record> type, Set<String> onlyInRecord) {
        Set<String> components = new HashSet<>();
        for (RecordComponent component : type.getRecordComponents()) {
            components.add(component.getName());
        }
        components.removeAll(onlyInRecord);
        assertThat(new HashSet<>(schema.path("properties").propertyNames())).as(type.getSimpleName()).isEqualTo(components);
    }
}
