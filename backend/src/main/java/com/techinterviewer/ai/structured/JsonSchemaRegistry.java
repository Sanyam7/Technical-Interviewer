package com.techinterviewer.ai.structured;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hand-written JSON Schemas of the model outputs, loaded once from {@code classpath:schemas/{name}.json}. They use
 * only the subset both Gemini and Ollama understand (object, array, string, integer, enum, required, min/max,
 * additionalProperties; Gemini is sent them without array bounds, which the output records enforce with {@code @Size}).
 * A schema is handed to the provider, which constrains generation to it; the output is still
 * validated in Java afterwards, because a schema-constrained model can still say something wrong.
 *
 * <p>{@link #get} returns a copy, so an agent may narrow it for one request (for example an {@code enum} of the key
 * point ids that exist) without touching the shared original.
 */
public final class JsonSchemaRegistry {

    public static final String LOCATION = "classpath*:schemas/*.json";

    private static final Pattern NAME = Pattern.compile(".*/schemas/([a-z][a-z0-9.-]*)\\.json$");

    private final Map<String, ObjectNode> schemas;

    public JsonSchemaRegistry(Map<String, ObjectNode> schemas) {
        schemas.forEach((name, schema) -> {
            if (!"object".equals(schema.path("type").asString(""))) {
                throw new IllegalStateException("schema " + name + " must describe a JSON object at the top level");
            }
        });
        this.schemas = Map.copyOf(schemas);
    }

    public static JsonSchemaRegistry load(ObjectMapper json) {
        try {
            Map<String, ObjectNode> schemas = new HashMap<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                Matcher name = NAME.matcher(resource.getURL().toString());
                if (!name.matches()) {
                    throw new IllegalStateException("schema file " + resource.getURL() + " is not at schemas/{name}.json");
                }
                JsonNode schema = json.readTree(resource.getContentAsString(StandardCharsets.UTF_8));
                if (!(schema instanceof ObjectNode object)) {
                    throw new IllegalStateException("schema " + name.group(1) + " is not a JSON object");
                }
                schemas.put(name.group(1), object);
            }
            return new JsonSchemaRegistry(schemas);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the JSON schemas", unreadable);
        }
    }

    /** A private copy of a schema. */
    public ObjectNode get(String name) {
        ObjectNode schema = schemas.get(name);
        if (schema == null) {
            throw new IllegalStateException("no JSON schema '" + name + "' under classpath:schemas/");
        }
        return schema.deepCopy();
    }

    public boolean contains(String name) {
        return schemas.containsKey(name);
    }
}
