package com.techinterviewer.ai.structured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.techinterviewer.ai.agents.output.AssessmentDraft;
import com.techinterviewer.ai.agents.output.DesignProbeDraft;
import com.techinterviewer.ai.agents.output.FollowUpDraft;
import com.techinterviewer.ai.agents.output.NarrativeDraft;
import com.techinterviewer.ai.agents.output.QuestionDraft;
import com.techinterviewer.ai.agents.output.ResumeDraft;
import com.techinterviewer.ai.provider.gemini.GeminiLlmProvider;
import com.techinterviewer.ai.support.AiStack;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Gemini is sent the schemas without their array bounds ({@link GeminiLlmProvider#withoutArrayBounds}), so on Gemini the
 * bounds hold only because the record an answer is bound to checks them too. This test keeps the two in step: every
 * {@code minItems}/{@code maxItems} in a schema is the {@code @Size} of the record component the array is bound to.
 */
class SchemaBoundsTest {

    private static final Map<String, Class<?>> BOUND_TO = Map.ofEntries(
            Map.entry("question.v1", QuestionDraft.class),
            Map.entry("question.v2", QuestionDraft.class),
            Map.entry("coding-question.v1", QuestionDraft.class),
            Map.entry("coding-question.v2", QuestionDraft.class),
            Map.entry("resume-question.v1", QuestionDraft.class),
            Map.entry("follow-up-question.v1", FollowUpDraft.class),
            Map.entry("follow-up-question.v2", FollowUpDraft.class),
            Map.entry("design-probe.v1", DesignProbeDraft.class),
            Map.entry("answer-evaluation.v1", AssessmentDraft.class),
            Map.entry("answer-evaluation.v2", AssessmentDraft.class),
            Map.entry("answer-evaluation.v3", AssessmentDraft.class),
            Map.entry("answer-evaluation-coding.v1", AssessmentDraft.class),
            Map.entry("answer-evaluation-system-design.v1", AssessmentDraft.class),
            Map.entry("criterion-rating.v1", AssessmentDraft.RatingDraft.class),
            Map.entry("design-criterion-rating.v1", AssessmentDraft.RatingDraft.class),
            Map.entry("report-narrative.v1", NarrativeDraft.class),
            Map.entry("report-narrative.v2", NarrativeDraft.class),
            Map.entry("resume-extraction.v1", ResumeDraft.class));

    private final JsonSchemaRegistry schemas = AiStack.schemas();

    @Test
    void everyArrayBoundOfASchemaIsAlsoEnforcedByItsRecord() {
        List<String> mismatches = new ArrayList<>();
        BOUND_TO.forEach((name, type) -> compare(name, schemas.get(name), type, mismatches));
        assertThat(mismatches).isEmpty();
    }

    @Test
    void geminiReceivesEveryShippedSchemaWithoutBounds() {
        for (String name : BOUND_TO.keySet()) {
            List<String> bounds = new ArrayList<>();
            collectBounds(GeminiLlmProvider.withoutArrayBounds(schemas.get(name)), name, bounds);
            assertThat(bounds).as(name).isEmpty();
        }
    }

    private static void compare(String path, JsonNode schema, Class<?> type, List<String> mismatches) {
        JsonNode properties = schema.path("properties");
        for (String property : properties.propertyNames()) {
            JsonNode child = properties.path(property);
            String at = path + "." + property;
            Field field = field(type, property, at);
            if (child.has("minItems") || child.has("maxItems")) {
                Size size = field.getAnnotation(Size.class);
                int min = child.path("minItems").asInt(0);
                int max = child.path("maxItems").asInt(Integer.MAX_VALUE);
                if (size == null && min == 0 && boundedByItsType(field, max)) {
                    continue;
                }
                if (size == null || size.min() != min || size.max() != max) {
                    mismatches.add(at + ": schema " + min + ".." + max + ", record "
                            + (size == null ? "no @Size" : size.min() + ".." + size.max()));
                }
            }
            JsonNode items = child.path("items");
            if (items.path("properties").isObject() && !items.path("properties").isEmpty()) {
                compare(at + "[]", items, elementType(field), mismatches);
            } else if (child.path("properties").isObject() && !child.path("properties").isEmpty()) {
                compare(at, child, field.getType(), mismatches);
            }
        }
    }

    /** A set of an enum holds at most one of each constant: with no more constants than the bound, the type is the bound. */
    private static boolean boundedByItsType(Field field, int max) {
        return Set.class.isAssignableFrom(field.getType())
                && field.getGenericType() instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element
                && element.isEnum() && element.getEnumConstants().length <= max;
    }

    private static Field field(Class<?> type, String property, String at) {
        try {
            return type.getDeclaredField(property);
        } catch (NoSuchFieldException missing) {
            return fail(at + " has no component in " + type.getSimpleName());
        }
    }

    private static Class<?> elementType(Field list) {
        Type generic = list.getGenericType();
        if (generic instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return fail(list + " is not a list of records");
    }

    private static void collectBounds(JsonNode schema, String at, List<String> found) {
        if (schema.has("minItems") || (schema.has("maxItems") && schema.path("maxItems").asInt() != 0)) {
            found.add(at);
        }
        JsonNode properties = schema.path("properties");
        for (String property : properties.propertyNames()) {
            collectBounds(properties.path(property), at + "." + property, found);
        }
        if (schema.path("items").isObject()) {
            collectBounds(schema.path("items"), at + "[]", found);
        }
    }
}
