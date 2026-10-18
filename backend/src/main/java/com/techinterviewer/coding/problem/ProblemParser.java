package com.techinterviewer.coding.problem;

import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Reads one problem file. Large test inputs are written with generators instead of literals, expanded here:
 *
 * <ul>
 *   <li>{@code {"$range": [from, to]}} or {@code [from, to, step]}: the ints from {@code from} to {@code to} inclusive;
 *   <li>{@code {"$repeat": [value, count]}}: an array of {@code count} copies of {@code value};
 *   <li>{@code {"$repeatString": [text, count]}}: {@code text} repeated {@code count} times;
 *   <li>{@code {"$concat": [a, b, ...]}}: the arrays (or strings) joined, each possibly a generator itself.
 * </ul>
 *
 * Expected outputs are literals, except where a literal would be enormous (a long string result); they may use the same
 * generators.
 */
public final class ProblemParser {

    /** Upper bound of one expanded value, against a typo such as a range to 10^9. */
    static final int MAX_GENERATED_ELEMENTS = 1_000_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ProblemParser() {
    }

    public static CodingProblem parse(String content, String source) {
        try {
            JsonNode root = JSON.readTree(content);
            Signature signature = signature(required(root, "signature"));
            List<ExpectedKeyPoint> keyPoints = new ArrayList<>();
            for (JsonNode point : required(root, "keyPoints")) {
                keyPoints.add(new ExpectedKeyPoint(text(point, "id"), text(point, "text"), List.of()));
            }
            List<TestCase> tests = new ArrayList<>();
            int index = 0;
            for (JsonNode test : required(root, "tests")) {
                index++;
                List<JsonNode> arguments = new ArrayList<>();
                for (JsonNode argument : required(test, "args")) {
                    arguments.add(expand(argument));
                }
                tests.add(new TestCase("t" + index, text(test, "name"), TestCategory.valueOf(text(test, "category")), arguments,
                        expand(required(test, "expected")), optionalText(test, "explanation")));
            }
            List<String> constraints = new ArrayList<>();
            for (JsonNode constraint : required(root, "constraints")) {
                constraints.add(constraint.asString());
            }
            JsonNode complexity = required(root, "complexity");
            return new CodingProblem(
                    text(root, "id"),
                    text(root, "title"),
                    required(root, "difficulty").asInt(),
                    text(root, "statement"),
                    constraints,
                    signature,
                    Comparison.valueOf(text(root, "comparison")),
                    required(root, "timeLimitMs").asInt(),
                    new CodingProblem.Complexity(text(complexity, "time"), text(complexity, "space")),
                    keyPoints,
                    tests);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("coding problem " + source + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    private static Signature signature(JsonNode node) {
        List<Signature.Parameter> parameters = new ArrayList<>();
        for (JsonNode parameter : required(node, "params")) {
            parameters.add(new Signature.Parameter(text(parameter, "name"), type(text(parameter, "type"))));
        }
        return new Signature(text(node, "name"), parameters, type(text(node, "returns")));
    }

    private static ValueType type(String token) {
        return ValueType.fromToken(token).orElseThrow(() -> new IllegalArgumentException("unknown type '" + token + "'"));
    }

    /** A literal is returned as is; a generator object is expanded. */
    static JsonNode expand(JsonNode node) {
        if (!node.isObject() || node.size() != 1) {
            return node;
        }
        Map.Entry<String, JsonNode> generator = node.properties().iterator().next();
        JsonNode spec = generator.getValue();
        return switch (generator.getKey()) {
            case "$range" -> range(spec);
            case "$repeat" -> repeat(spec);
            case "$repeatString" -> NODES.stringNode(spec.get(0).asString().repeat(count(spec.get(1).asInt(), spec.get(0).asString().length())));
            case "$concat" -> concat(spec);
            default -> node;
        };
    }

    private static ArrayNode range(JsonNode spec) {
        long from = spec.get(0).asLong();
        long to = spec.get(1).asLong();
        long step = spec.size() > 2 ? spec.get(2).asLong() : 1;
        if (step == 0 || (to - from) / step < 0) {
            throw new IllegalArgumentException("$range " + spec + " is empty or endless");
        }
        count((to - from) / step + 1, 1);
        ArrayNode array = NODES.arrayNode();
        for (long value = from; step > 0 ? value <= to : value >= to; value += step) {
            array.add(value);
        }
        return array;
    }

    private static ArrayNode repeat(JsonNode spec) {
        JsonNode value = expand(spec.get(0));
        int times = spec.get(1).asInt();
        count(times, 1);
        ArrayNode array = NODES.arrayNode();
        for (int i = 0; i < times; i++) {
            array.add(value);
        }
        return array;
    }

    private static JsonNode concat(JsonNode spec) {
        List<JsonNode> parts = new ArrayList<>();
        for (JsonNode part : spec) {
            parts.add(expand(part));
        }
        if (!parts.isEmpty() && parts.getFirst().isString()) {
            StringBuilder text = new StringBuilder();
            parts.forEach(part -> text.append(part.asString()));
            count(text.length(), 1);
            return NODES.stringNode(text.toString());
        }
        ArrayNode array = NODES.arrayNode();
        parts.forEach(part -> part.forEach(array::add));
        count(array.size(), 1);
        return array;
    }

    private static int count(long elements, int width) {
        if (elements < 0 || elements * width > MAX_GENERATED_ELEMENTS) {
            throw new IllegalArgumentException("a generated value may have at most " + MAX_GENERATED_ELEMENTS + " elements");
        }
        return (int) elements;
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("'" + field + "' is missing");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isString()) {
            throw new IllegalArgumentException("'" + field + "' must be a string");
        }
        return value.asString();
    }

    private static @Nullable String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
