package com.techinterviewer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Makes the generated OpenAPI document say what the API really does, so a client generated from it type-checks:
 *
 * <ul>
 *   <li><b>Nullability</b> comes from the records themselves: a component annotated {@code @Nullable} accepts
 *       {@code null} (an object reference becomes {@code oneOf [$ref, null]}; the generator would otherwise emit the
 *       unsatisfiable {@code type: null} next to a {@code $ref}), every other component never does.
 *   <li><b>Required properties</b> of every schema that a response returns are all its non-nullable components (the
 *       JSON always contains them), so generated types do not make every field optional.
 *   <li>Integer enums list integers, every header declares a schema, and every error response is the problem document.
 * </ul>
 */
final class OpenApiSchemaPolisher {

    private static final String SCHEMA_PREFIX = "#/components/schemas/";
    private static final String NULL_TYPE = "null";

    private final Map<String, Class<?>> records;

    private OpenApiSchemaPolisher(Map<String, Class<?>> records) {
        this.records = records;
    }

    /** Polishes against the records of this application, found on the classpath by their simple name. */
    static void polish(OpenAPI openApi) {
        new OpenApiSchemaPolisher(scanRecords()).apply(openApi);
    }

    static void polish(OpenAPI openApi, Map<String, Class<?>> records) {
        new OpenApiSchemaPolisher(records).apply(openApi);
    }

    private static Map<String, Class<?>> scanRecords() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        Map<String, Class<?>> found = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.techinterviewer")) {
            Class<?> type = ClassUtils.resolveClassName(definition.getBeanClassName(), OpenApiSchemaPolisher.class.getClassLoader());
            if (found.putIfAbsent(type.getSimpleName(), type) != null) {
                ambiguous.add(type.getSimpleName());
            }
        }
        ambiguous.forEach(found::remove);
        return found;
    }

    @SuppressWarnings("rawtypes")
    private void apply(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents() == null || openApi.getComponents().getSchemas() == null
                ? Map.of()
                : openApi.getComponents().getSchemas();
        schemas.forEach((name, schema) -> {
            Class<?> type = records.get(name);
            if (type != null) {
                applyNullability(schema, type);
            }
            integerEnums(schema);
        });
        markRequired(openApi, schemas);
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(item -> item.readOperations().forEach(OpenApiSchemaPolisher::polishOperation));
        }
    }

    // ---- nullability ----------------------------------------------------------------------------------------

    private static boolean nullable(RecordComponent component) {
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class) || component.isAnnotationPresent(Nullable.class);
    }

    @SuppressWarnings("rawtypes")
    private static void applyNullability(Schema<?> schema, Class<?> record) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        for (RecordComponent component : record.getRecordComponents()) {
            Schema property = properties.get(component.getName());
            if (property == null) {
                continue;
            }
            if (nullable(component)) {
                allowNull(property);
            } else {
                forbidNull(property);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void allowNull(Schema property) {
        if (property.get$ref() != null) {
            String ref = property.get$ref();
            String description = property.getDescription();
            property.set$ref(null);
            property.setTypes(null);
            property.setType(null);
            property.setOneOf(new ArrayList<>(List.of(new Schema<>().$ref(ref), nullSchema())));
            property.setDescription(description);
            return;
        }
        if (property.getOneOf() != null && !property.getOneOf().isEmpty()) {
            boolean hasNull = ((List<Schema>) property.getOneOf()).stream().anyMatch(member -> isNullSchema(member));
            if (!hasNull) {
                property.addOneOfItem(nullSchema());
            }
            return;
        }
        Set<String> types = typesOf(property);
        if (types.add(NULL_TYPE)) {
            property.setTypes(types);
        }
        List<Object> values = property.getEnum();
        if (values != null && !values.isEmpty() && !values.contains(null)) {
            List<Object> withNull = new ArrayList<>(values);
            withNull.add(null);
            property.setEnum(withNull);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void forbidNull(Schema property) {
        if (property.get$ref() != null && NULL_TYPE.equals(property.getType())) {
            property.setType(null);
            property.setTypes(null);
        }
        Set<String> types = typesOf(property);
        if (types.remove(NULL_TYPE) && !types.isEmpty()) {
            property.setTypes(types);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Set<String> typesOf(Schema property) {
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        return types;
    }

    @SuppressWarnings("rawtypes")
    private static Schema nullSchema() {
        Schema<?> schema = new Schema<>();
        schema.addType(NULL_TYPE);
        return schema;
    }

    @SuppressWarnings("rawtypes")
    private static boolean isNullSchema(Schema schema) {
        return schema.get$ref() == null && (NULL_TYPE.equals(schema.getType())
                || schema.getTypes() != null && schema.getTypes().contains(NULL_TYPE));
    }

    // ---- integer enums --------------------------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void integerEnums(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        for (Schema property : properties.values()) {
            Set<String> types = typesOf(property);
            List<Object> values = property.getEnum();
            if (values == null || !(types.contains("integer") || types.contains("number"))) {
                continue;
            }
            List<Object> converted = new ArrayList<>();
            for (Object value : values) {
                converted.add(value instanceof String text && text.matches("-?\\d+") ? (Object) Long.valueOf(text) : value);
            }
            property.setEnum(converted);
        }
    }

    // ---- required properties --------------------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void markRequired(OpenAPI openApi, Map<String, Schema> schemas) {
        Deque<String> pending = new ArrayDeque<>();
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(item -> item.readOperations().forEach(operation -> {
                if (operation.getResponses() != null) {
                    operation.getResponses().forEach((status, response) -> {
                        if (!status.startsWith("4") && !status.startsWith("5") && response.getContent() != null) {
                            response.getContent().values().forEach(media -> refsOf(media.getSchema(), pending));
                        }
                    });
                }
            }));
        }
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.pop();
            Schema schema = schemas.get(name);
            if (schema == null || !seen.add(name)) {
                continue;
            }
            Class<?> type = records.get(name);
            if (type != null && (schema.getRequired() == null || schema.getRequired().isEmpty()) && schema.getProperties() != null) {
                List<String> required = new ArrayList<>();
                for (RecordComponent component : type.getRecordComponents()) {
                    if (!nullable(component) && schema.getProperties().containsKey(component.getName())) {
                        required.add(component.getName());
                    }
                }
                if (!required.isEmpty()) {
                    schema.setRequired(required);
                }
            }
            if (schema.getProperties() != null) {
                ((Map<String, Schema>) schema.getProperties()).values().forEach(property -> refsOf(property, pending));
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void refsOf(@Nullable Schema schema, Deque<String> into) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null && schema.get$ref().startsWith(SCHEMA_PREFIX)) {
            into.push(schema.get$ref().substring(SCHEMA_PREFIX.length()));
        }
        refsOf(schema.getItems(), into);
        for (List<Schema> group : java.util.Arrays.asList(schema.getOneOf(), schema.getAllOf(), schema.getAnyOf())) {
            if (group != null) {
                group.forEach(member -> refsOf(member, into));
            }
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            refsOf(additional, into);
        }
    }

    // ---- operations -----------------------------------------------------------------------------------------

    private static void polishOperation(Operation operation) {
        if (operation.getResponses() == null) {
            return;
        }
        operation.getResponses().forEach((status, response) -> {
            headerSchemas(response);
            if (status.startsWith("4") || status.startsWith("5")) {
                problemContent(response);
            }
        });
    }

    private static void headerSchemas(ApiResponse response) {
        if (response.getHeaders() == null) {
            return;
        }
        for (Map.Entry<String, Header> entry : response.getHeaders().entrySet()) {
            Header header = entry.getValue();
            if (header.getSchema() == null && header.getContent() == null) {
                header.setSchema("Retry-After".equalsIgnoreCase(entry.getKey()) ? new IntegerSchema() : new StringSchema());
            }
        }
    }

    /** Every error answers application/problem+json, whatever the annotation said or the converter chose. */
    private static void problemContent(ApiResponse response) {
        Content content = response.getContent();
        boolean alreadyProblem = content != null && content.size() == 1 && content.containsKey(OpenApiConfig.PROBLEM_MEDIA_TYPE);
        if (!alreadyProblem) {
            response.setContent(new Content().addMediaType(OpenApiConfig.PROBLEM_MEDIA_TYPE,
                    new MediaType().schema(new Schema<>().$ref(SCHEMA_PREFIX + OpenApiConfig.PROBLEM_SCHEMA))));
        }
    }
}
