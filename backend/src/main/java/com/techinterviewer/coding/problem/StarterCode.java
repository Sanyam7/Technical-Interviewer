package com.techinterviewer.coding.problem;

import java.util.stream.Collectors;

/** The code the editor starts with: the problem's function, empty, in the interview's language. */
public final class StarterCode {

    private static final String TODO = "Write your solution here.";

    private StarterCode() {
    }

    public static String render(Signature signature, CodeLanguage language) {
        return switch (language) {
            case JAVA -> java(signature);
            case JAVASCRIPT -> javascript(signature);
            case TYPESCRIPT -> typescript(signature);
            case PYTHON -> python(signature);
            case GO -> go(signature);
        };
    }

    private static String java(Signature signature) {
        String parameters = signature.parameters().stream()
                .map(parameter -> javaType(parameter.type()) + " " + parameter.name())
                .collect(Collectors.joining(", "));
        return "import java.util.*;\n\nclass Solution {\n    public " + javaType(signature.returns()) + " " + signature.name() + "(" + parameters
                + ") {\n        // " + TODO + "\n    }\n}\n";
    }

    private static String javascript(Signature signature) {
        StringBuilder doc = new StringBuilder("/**\n");
        signature.parameters().forEach(parameter -> doc.append(" * @param {").append(tsType(parameter.type())).append("} ")
                .append(parameter.name()).append('\n'));
        doc.append(" * @return {").append(tsType(signature.returns())).append("}\n */\n");
        String parameters = signature.parameters().stream().map(Signature.Parameter::name).collect(Collectors.joining(", "));
        return doc + "function " + signature.name() + "(" + parameters + ") {\n  // " + TODO + "\n}\n";
    }

    private static String typescript(Signature signature) {
        String parameters = signature.parameters().stream()
                .map(parameter -> parameter.name() + ": " + tsType(parameter.type()))
                .collect(Collectors.joining(", "));
        return "function " + signature.name() + "(" + parameters + "): " + tsType(signature.returns()) + " {\n  // " + TODO + "\n}\n";
    }

    private static String python(Signature signature) {
        String parameters = signature.parameters().stream()
                .map(parameter -> parameter.name() + ": " + pythonType(parameter.type()))
                .collect(Collectors.joining(", "));
        return "class Solution:\n    def " + signature.name() + "(self, " + parameters + ") -> " + pythonType(signature.returns())
                + ":\n        # " + TODO + "\n        pass\n";
    }

    private static String go(Signature signature) {
        String parameters = signature.parameters().stream()
                .map(parameter -> parameter.name() + " " + goType(parameter.type()))
                .collect(Collectors.joining(", "));
        return "func " + signature.name() + "(" + parameters + ") " + goType(signature.returns()) + " {\n\t// " + TODO + "\n}\n";
    }

    static String javaType(ValueType type) {
        return switch (type) {
            case INT -> "int";
            case LONG -> "long";
            case BOOLEAN -> "boolean";
            case STRING -> "String";
            case INT_ARRAY -> "int[]";
            case STRING_ARRAY -> "String[]";
            case INT_MATRIX -> "int[][]";
        };
    }

    static String tsType(ValueType type) {
        return switch (type) {
            case INT, LONG -> "number";
            case BOOLEAN -> "boolean";
            case STRING -> "string";
            case INT_ARRAY -> "number[]";
            case STRING_ARRAY -> "string[]";
            case INT_MATRIX -> "number[][]";
        };
    }

    private static String pythonType(ValueType type) {
        return switch (type) {
            case INT, LONG -> "int";
            case BOOLEAN -> "bool";
            case STRING -> "str";
            case INT_ARRAY -> "list[int]";
            case STRING_ARRAY -> "list[str]";
            case INT_MATRIX -> "list[list[int]]";
        };
    }

    private static String goType(ValueType type) {
        return switch (type) {
            case INT -> "int";
            case LONG -> "int64";
            case BOOLEAN -> "bool";
            case STRING -> "string";
            case INT_ARRAY -> "[]int";
            case STRING_ARRAY -> "[]string";
            case INT_MATRIX -> "[][]int";
        };
    }
}
