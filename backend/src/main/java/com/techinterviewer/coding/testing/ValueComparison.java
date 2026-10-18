package com.techinterviewer.coding.testing;

import com.techinterviewer.coding.problem.Comparison;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Whether a returned value equals the expected one, by the problem's comparison. Numbers compare by value. */
final class ValueComparison {

    private ValueComparison() {
    }

    static boolean matches(JsonNode actual, JsonNode expected, Comparison comparison) {
        if (comparison == Comparison.UNORDERED && actual.isArray() && expected.isArray()) {
            return sorted(actual).equals(sorted(expected));
        }
        return same(actual, expected);
    }

    static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<JsonNode> left = a.iterator();
            Iterator<JsonNode> right = b.iterator();
            while (left.hasNext()) {
                if (!same(left.next(), right.next())) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static List<String> sorted(JsonNode array) {
        List<String> elements = new ArrayList<>();
        array.forEach(element -> elements.add(canonical(element)));
        elements.sort(null);
        return elements;
    }

    private static String canonical(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(element -> parts.add(canonical(element)));
            return "[" + String.join(",", parts) + "]";
        }
        return node.toString();
    }
}
