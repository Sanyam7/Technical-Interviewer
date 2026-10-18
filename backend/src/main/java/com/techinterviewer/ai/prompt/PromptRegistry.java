package com.techinterviewer.ai.prompt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Every prompt template of the application, loaded once at startup from {@code classpath:prompts/{id}/v{n}.md}.
 * Agents ask for the latest version of their prompt by id; earlier versions stay loaded and in the repository so
 * that recorded calls and evaluations remain traceable to their exact text.
 *
 * <p>File format:
 * <pre>
 * ---
 * id: answer-evaluation
 * version: v1
 * schema: answer-evaluation.v1
 * ---
 * === SYSTEM ===
 * ...
 * === USER ===
 * ... {{placeholders}} ...
 * </pre>
 * The front matter must agree with the file's location; a malformed file stops the application at startup.
 */
public final class PromptRegistry {

    public static final String LOCATION = "classpath*:prompts/*/v*.md";

    private static final Pattern PATH = Pattern.compile(".*/prompts/([a-z][a-z0-9-]*)/(v[1-9][0-9]*)\\.md$");
    private static final String SYSTEM_MARKER = "=== SYSTEM ===";
    private static final String USER_MARKER = "=== USER ===";

    private final Map<String, List<PromptTemplate>> byId;

    public PromptRegistry(List<PromptTemplate> templates) {
        Map<String, List<PromptTemplate>> grouped = new HashMap<>();
        for (PromptTemplate template : templates) {
            List<PromptTemplate> versions = grouped.computeIfAbsent(template.id(), id -> new ArrayList<>());
            if (versions.stream().anyMatch(other -> other.version().equals(template.version()))) {
                throw new IllegalStateException("prompt " + template.id() + "/" + template.version() + " is defined twice");
            }
            versions.add(template);
        }
        grouped.values().forEach(versions -> versions.sort(Comparator.comparingInt(PromptRegistry::number)));
        this.byId = Map.copyOf(grouped);
    }

    /** Loads every template from the classpath. */
    public static PromptRegistry load() {
        try {
            List<PromptTemplate> templates = new ArrayList<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                String location = resource.getURL().toString();
                Matcher path = PATH.matcher(location);
                if (!path.matches()) {
                    throw new IllegalStateException("prompt file " + location + " is not at prompts/{id}/v{n}.md");
                }
                PromptTemplate template = parse(resource.getContentAsString(StandardCharsets.UTF_8), location);
                if (!template.id().equals(path.group(1)) || !template.version().equals(path.group(2))) {
                    throw new IllegalStateException("prompt file " + location + " declares " + template.id() + "/" + template.version());
                }
                templates.add(template);
            }
            return new PromptRegistry(templates);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the prompt templates", unreadable);
        }
    }

    /** The newest version of a prompt. */
    public PromptTemplate latest(String id) {
        List<PromptTemplate> versions = byId.get(id);
        if (versions == null) {
            throw new IllegalStateException("no prompt template '" + id + "' under classpath:prompts/");
        }
        return versions.getLast();
    }

    /** A given version, for tracing a recorded call back to its text. */
    public Optional<PromptTemplate> find(String id, String version) {
        return byId.getOrDefault(id, List.of()).stream().filter(template -> template.version().equals(version)).findFirst();
    }

    static PromptTemplate parse(String content, String location) {
        String text = content.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw new IllegalStateException(location + ": a prompt file starts with front matter between '---' lines");
        }
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalStateException(location + ": the front matter is not closed by '---'");
        }
        Map<String, String> front = new HashMap<>();
        for (String line : text.substring(4, end).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 1) {
                throw new IllegalStateException(location + ": front matter line '" + line + "' is not 'key: value'");
            }
            front.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String body = text.substring(end + 5);
        int system = body.indexOf(SYSTEM_MARKER);
        int user = body.indexOf(USER_MARKER);
        if (system < 0 || user < system || body.indexOf(SYSTEM_MARKER, system + 1) >= 0 || body.indexOf(USER_MARKER, user + 1) >= 0) {
            throw new IllegalStateException(location + ": the body needs one '" + SYSTEM_MARKER + "' followed by one '" + USER_MARKER + "'");
        }
        try {
            return new PromptTemplate(
                    front.get("id"),
                    front.get("version"),
                    front.get("schema"),
                    body.substring(system + SYSTEM_MARKER.length(), user).strip(),
                    body.substring(user + USER_MARKER.length()).strip());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(location + ": " + invalid.getMessage(), invalid);
        }
    }

    private static int number(PromptTemplate template) {
        return Integer.parseInt(template.version().substring(1));
    }

    /** Every loaded template, for startup checks. */
    public List<PromptTemplate> all() {
        return byId.values().stream().flatMap(List::stream).toList();
    }
}
