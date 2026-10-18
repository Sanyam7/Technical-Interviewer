package com.techinterviewer.coding.problem;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** How a test reads for a person: {@code nums = [2,7,11,15], target = 9} and {@code [0,1]}. */
public final class ExampleText {

    /** Longest value shown before it is cut. */
    public static final int MAX_LENGTH = 300;

    private ExampleText() {
    }

    public static String input(Signature signature, TestCase test) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < signature.parameters().size(); i++) {
            parts.add(signature.parameters().get(i).name() + " = " + value(test.arguments().get(i)));
        }
        return shorten(String.join(", ", parts));
    }

    public static String value(JsonNode value) {
        return shorten(value.toString());
    }

    public static String shorten(String text) {
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + "…";
    }
}
