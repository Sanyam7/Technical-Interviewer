package com.techinterviewer.ai.structured;

import com.techinterviewer.ai.provider.FinishReason;
import com.techinterviewer.ai.provider.LlmResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.type.LogicalType;

/**
 * Turns a model's text into a validated Java value, or into a list of problems that the repair attempt can show the
 * model. Nothing here manipulates strings to guess at structure: the JSON is read by Jackson, bound to a record with
 * the application's strict settings (unknown properties, coerced scalars, numbers for enums and fractions for integers
 * are all rejected) and checked with Bean Validation.
 *
 * <p>Handled shapes: an empty answer; an answer cut off by the token limit; JSON inside a Markdown fence or with prose
 * around it (the first JSON object is read, the rest ignored: models served without native schema support do this);
 * malformed or truncated JSON; wrong types, missing or unknown fields, unknown enum constants; values out of range.
 */
public final class OutputParser {

    /** Longest single problem text kept, so a repair prompt stays short. */
    static final int MAX_PROBLEM_LENGTH = 300;

    private final ObjectMapper json;
    private final ObjectReader firstValue;
    private final Validator validator;

    /**
     * @param json the application's mapper; the parser derives its own strict settings from it, so model output is
     *             read strictly whatever the application configures for its HTTP API
     */
    public OutputParser(ObjectMapper json, Validator validator) {
        this.json = json.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
        // Reads the first JSON value and ignores whatever follows it (a closing fence, a sentence of prose).
        this.firstValue = this.json.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    /** @throws OutputRejectedException with every problem found */
    public <T> T parse(LlmResponse response, Class<T> type) {
        return parse(response, type, document -> List.of());
    }

    /**
     * @param documentCheck looks at the parsed JSON before it is bound to the record, e.g. for a recital of the prompt;
     *                      returns the problems, empty when the document is acceptable
     * @throws OutputRejectedException with every problem found
     */
    public <T> T parse(LlmResponse response, Class<T> type, Function<JsonNode, List<String>> documentCheck) {
        String text = response.text();
        if (response.finishReason() == FinishReason.MAX_TOKENS) {
            throw new OutputRejectedException(List.of("the answer was cut off by the output token limit; answer more concisely"));
        }
        if (text.isBlank()) {
            throw new OutputRejectedException(List.of("the answer was empty"));
        }
        int start = text.indexOf('{');
        if (start < 0) {
            throw new OutputRejectedException(List.of("the answer contains no JSON object"));
        }
        JsonNode document;
        try (JsonParser parser = firstValue.createParser(text.substring(start))) {
            document = firstValue.readValue(parser);
        } catch (JacksonException malformed) {
            throw new OutputRejectedException(List.of("the answer is not valid JSON: " + shorten(malformed.getOriginalMessage())));
        }
        if (document == null || !document.isObject()) {
            throw new OutputRejectedException(List.of("the answer is not a JSON object"));
        }
        List<String> unacceptable = documentCheck.apply(document);
        if (!unacceptable.isEmpty()) {
            throw new OutputRejectedException(unacceptable);
        }
        T value;
        try {
            value = json.treeToValue(document, type);
        } catch (JacksonException wrongShape) {
            String path = wrongShape.getPathReference();
            throw new OutputRejectedException(List.of(shorten("the JSON does not match the schema"
                    + (path == null || path.isBlank() ? "" : " at " + simplify(path)) + ": " + wrongShape.getOriginalMessage())));
        } catch (IllegalArgumentException invalid) {
            // A record's own constructor rejected a value.
            throw new OutputRejectedException(List.of(shorten(invalid.getMessage())));
        }
        List<String> problems = new ArrayList<>();
        validator.validate(value).stream()
                .sorted(Comparator.comparing((ConstraintViolation<T> violation) -> violation.getPropertyPath().toString()))
                .forEach(violation -> problems.add(shorten(violation.getPropertyPath() + ": " + violation.getMessage())));
        if (!problems.isEmpty()) {
            throw new OutputRejectedException(problems);
        }
        return value;
    }

    static String shorten(String message) {
        String firstLine = message == null ? "unknown error" : message.strip().split("\n", 2)[0];
        return firstLine.length() > MAX_PROBLEM_LENGTH ? firstLine.substring(0, MAX_PROBLEM_LENGTH) + "..." : firstLine;
    }

    /** Jackson's path reference names Java classes; the model only needs the property path. */
    private static String simplify(String path) {
        return path.replaceAll("[A-Za-z0-9_.$]+\\[", "[").replaceAll("^->", "");
    }
}
