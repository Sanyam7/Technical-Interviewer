package com.techinterviewer.coding;

import com.techinterviewer.coding.problem.CodeLanguage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** The reference solutions of the problem bank (test resources): one per problem and executable language. */
public final class References {

    private References() {
    }

    public static String solution(String problemId, CodeLanguage language) {
        String extension = switch (language) {
            case JAVA -> "java";
            case JAVASCRIPT -> "js";
            case TYPESCRIPT -> "ts";
            default -> throw new IllegalArgumentException(language + " has no reference solutions");
        };
        try {
            return new ClassPathResource("coding-reference/" + language.code() + "/" + problemId + "." + extension)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException("no " + language.code() + " reference solution for " + problemId, missing);
        }
    }
}
