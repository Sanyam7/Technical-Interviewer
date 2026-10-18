package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps credentials out of the repository (SECURITY.md, SEC-1). The project's rule is that no API key, secret or password is ever
 * committed: this scan reads every text file of the working tree (the git-ignored {@code .env} excepted, which is where secrets
 * belong) and fails on anything shaped like a credential, and on a non-empty secret in {@code .env.example}. It cannot prove a
 * secret is absent, only catch the well-known shapes; the first line of defence is that nothing is ever pasted into a file.
 */
class SecretScanTest {

    /** Shapes of real credentials. Written so that this file does not match itself. */
    private static final List<Pattern> SHAPES = List.of(
            Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
            Pattern.compile("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9]{32,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{20,}"));

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "node_modules", "target", "dist", "coverage", ".idea", ".vscode",
            "reports", "test-run", ".mvn");
    private static final Set<String> SKIPPED_FILES = Set.of(".env", ".DS_Store");
    private static final Set<String> BINARY = Set.of("png", "jpg", "jpeg", "gif", "ico", "pdf", "docx", "zip", "jar", "gz", "woff", "woff2",
            "ttf", "class", "svg");

    private static Path root() {
        return Paths.get(System.getProperty("repository.root", "..")).toAbsolutePath().normalize();
    }

    private static List<Path> textFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> root.relativize(path).getNameCount() > 0)
                    .filter(path -> StreamOfNames.names(root.relativize(path)).noneMatch(SKIPPED_DIRECTORIES::contains))
                    .filter(path -> !SKIPPED_FILES.contains(path.getFileName().toString()))
                    .filter(path -> !BINARY.contains(extension(path)))
                    .forEach(files::add);
        }
        return files;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /** The directory names of a relative path. */
    private static final class StreamOfNames {
        static Stream<String> names(Path relative) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                names.add(relative.getName(i).toString());
            }
            return names.stream();
        }
    }

    @Test
    void noFileOfTheRepositoryContainsAShapeOfARealCredential() throws IOException {
        Path root = root();
        List<Path> files = textFiles(root);
        assertThat(files).as("the repository was found at " + root).hasSizeGreaterThan(200);

        List<String> findings = new ArrayList<>();
        for (Path file : files) {
            if (Files.size(file) > 2_000_000) {
                continue;
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (Pattern shape : SHAPES) {
                if (shape.matcher(text).find()) {
                    findings.add(root.relativize(file) + " matches " + shape.pattern());
                }
            }
        }
        assertThat(findings).as("files that look like they contain a credential").isEmpty();
    }

    @Test
    void theExampleEnvironmentFileHoldsNoSecret() throws IOException {
        List<String> lines = Files.readAllLines(root().resolve(".env.example"));
        assertThat(filled(lines, ".*(KEY|SECRET|TOKEN)$")).as("key, secret and token variables must be empty in .env.example").isEmpty();
        assertThat(lines).anyMatch(line -> line.equals("JWT_SECRET=")).anyMatch(line -> line.equals("GEMINI_API_KEY="));
        assertThat(lines).anyMatch(line -> line.equals("POSTGRES_APP_PASSWORD="));
    }

    @Test
    void theProductionExampleFileHoldsNoSecretNotEvenAPlaceholderPassword() throws IOException {
        List<String> lines = Files.readAllLines(root().resolve(".env.production.example"));
        assertThat(filled(lines, ".*(KEY|SECRET|TOKEN|PASSWORD)$")).as("secrets must be empty in .env.production.example").isEmpty();
        assertThat(lines).contains("JWT_SECRET=", "GEMINI_API_KEY=", "POSTGRES_PASSWORD=", "POSTGRES_APP_PASSWORD=");
    }

    /** The assignments to a variable whose name matches {@code names} that have a value. */
    private static List<String> filled(List<String> lines, String names) {
        return lines.stream().filter(line -> !line.startsWith("#") && line.contains("="))
                .filter(line -> line.split("=", 2)[0].matches(names))
                .filter(line -> line.split("=", 2).length > 1 && !line.split("=", 2)[1].isBlank()).toList();
    }

    @Test
    void theShapesRecogniseWhatTheyAreMeantTo() {
        String googleKey = "AI" + "za" + "SyA-1234567890abcdefghijklmnopqrstuv";
        String privateKey = "-----BEGIN " + "PRIVATE KEY-----";
        assertThat(SHAPES).anyMatch(shape -> shape.matcher("key = " + googleKey).find());
        assertThat(SHAPES).anyMatch(shape -> shape.matcher(privateKey).find());
        assertThat(SHAPES).noneMatch(shape -> shape.matcher("GEMINI_API_KEY=").find());
        assertThat(SHAPES).noneMatch(shape -> shape.matcher("the key is read from the environment").find());
    }
}
