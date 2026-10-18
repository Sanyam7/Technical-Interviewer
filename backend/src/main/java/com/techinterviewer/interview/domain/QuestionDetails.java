package com.techinterviewer.interview.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Extra, candidate-visible material of a coding (or design) question, stored in {@code questions.details}.
 *
 * @param constraints      limits of the problem, for example {@code 1 <= n <= 10^5}
 * @param examples         worked examples
 * @param starterSignature code skeleton offered in the editor, if any
 * @param language         programming language the answer must be written in (coding only)
 */
public record QuestionDetails(
        List<String> constraints,
        List<CodingExample> examples,
        @Nullable String starterSignature,
        @Nullable String language) {

    public static final int MAX_CONSTRAINTS = 20;
    public static final int MAX_EXAMPLES = 10;
    public static final int MAX_CONSTRAINT_LENGTH = 500;
    public static final int MAX_SIGNATURE_LENGTH = 2000;

    public QuestionDetails {
        constraints = Immutables.list(constraints);
        examples = Immutables.list(examples);
        if (constraints.size() > MAX_CONSTRAINTS) {
            throw new IllegalArgumentException("constraints must have at most " + MAX_CONSTRAINTS + " entries");
        }
        constraints.forEach(constraint -> Require.notBlank(constraint, "constraint", MAX_CONSTRAINT_LENGTH));
        if (examples.size() > MAX_EXAMPLES) {
            throw new IllegalArgumentException("examples must have at most " + MAX_EXAMPLES + " entries");
        }
        starterSignature = Require.nullOrNotBlank(starterSignature, "starterSignature", MAX_SIGNATURE_LENGTH);
        language = Require.nullOrNotBlank(language, "language", 20);
    }
}
