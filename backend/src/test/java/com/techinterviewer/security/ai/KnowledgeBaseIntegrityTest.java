package com.techinterviewer.security.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.ai.agents.guard.InjectionHeuristics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The curated knowledge base is retrieved into prompts as reference material. It is fenced and redacted like every untrusted
 * text, but a document that tries to instruct a model must not be committed in the first place (SECURITY.md, AI-4): this test
 * runs the injection heuristics over every shipped document and fails the build on a match.
 */
class KnowledgeBaseIntegrityTest {

    /** The shipped documents, as the application finds them on its classpath. */
    private static Path root() throws Exception {
        return Paths.get(KnowledgeBaseIntegrityTest.class.getResource("/knowledge-base").toURI());
    }

    @Test
    void noShippedDocumentAddressesAModel() throws Exception {
        Path root = root();
        List<String> offending = new ArrayList<>();
        int documents = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                documents++;
                String text = Files.readString(file);
                for (String paragraph : text.split("\\n\\s*\\n")) {
                    if (InjectionHeuristics.suspected(paragraph)) {
                        offending.add(root.relativize(file) + ": " + paragraph.strip().replaceAll("\\s+", " ").substring(0, Math.min(80, paragraph.strip().length())));
                    }
                }
            }
        }
        assertThat(documents).as("the knowledge base was found").isGreaterThan(40);
        assertThat(offending).as("documents with text that addresses a model").isEmpty();
    }
}
