package com.techinterviewer.coding.problem;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The function the candidate implements: its name, parameters and result type. In Java it is an instance method of a
 * class {@code Solution}; in JavaScript and TypeScript a top-level function.
 */
public record Signature(String name, List<Parameter> parameters, ValueType returns) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    public static final int MAX_PARAMETERS = 4;

    /** One parameter: a name valid in every supported language, and its type. */
    public record Parameter(String name, ValueType type) {

        public Parameter {
            identifier(name, "parameter name");
            Require.nonNull(type, "type");
        }
    }

    public Signature {
        identifier(name, "function name");
        parameters = Immutables.list(parameters);
        Require.between(parameters.size(), 1, MAX_PARAMETERS, "number of parameters");
        Set<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (!names.add(parameter.name())) {
                throw new IllegalArgumentException("parameter " + parameter.name() + " is declared twice");
            }
        }
        Require.nonNull(returns, "returns");
    }

    private static void identifier(String value, String what) {
        Require.notBlank(value, what);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must be a lower camel case identifier but was '" + value + "'");
        }
    }
}
