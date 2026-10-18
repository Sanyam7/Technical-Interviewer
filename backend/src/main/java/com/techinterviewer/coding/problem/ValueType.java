package com.techinterviewer.coding.problem;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * The types a problem's parameters and result may have. Deliberately small: every type has an obvious counterpart in
 * each supported language and a canonical JSON form, so the harness can pass arguments and report results without a
 * serialisation library.
 */
public enum ValueType {
    INT("int"),
    LONG("long"),
    BOOLEAN("boolean"),
    STRING("string"),
    INT_ARRAY("int[]"),
    STRING_ARRAY("string[]"),
    INT_MATRIX("int[][]");

    private final String token;

    ValueType(String token) {
        this.token = token;
    }

    /** The name used in problem files, for example {@code int[]}. */
    public String token() {
        return token;
    }

    public static Optional<ValueType> fromToken(String token) {
        return Arrays.stream(values()).filter(type -> type.token.equals(token)).findFirst();
    }

    /** Whether a JSON value is a value of this type (ints must fit 32 bits). */
    public boolean accepts(JsonNode value) {
        return switch (this) {
            case INT -> isInt(value);
            case LONG -> value.isIntegralNumber() && value.canConvertToLong();
            case BOOLEAN -> value.isBoolean();
            case STRING -> value.isString();
            case INT_ARRAY -> value.isArray() && all(value, ValueType::isInt);
            case STRING_ARRAY -> value.isArray() && all(value, JsonNode::isString);
            case INT_MATRIX -> value.isArray() && all(value, row -> row.isArray() && all(row, ValueType::isInt));
        };
    }

    private static boolean isInt(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt();
    }

    private static boolean all(JsonNode array, Predicate<JsonNode> test) {
        for (JsonNode element : array) {
            if (!test.test(element)) {
                return false;
            }
        }
        return true;
    }
}
