package com.techinterviewer.ai.prompt;

import com.techinterviewer.common.validation.Require;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One versioned prompt, loaded from {@code classpath:prompts/{id}/v{n}.md}. The id and the version are stamped on
 * every call record and on every evaluation, so any score can be traced to the exact prompt that produced it. A
 * change of wording is a new file with the next version, never an edit of a released one.
 *
 * @param id      stable name, for example {@code answer-evaluation}
 * @param version {@code v1}, {@code v2}, ...
 * @param schema  name of the JSON Schema the answer must follow ({@code classpath:schemas/{schema}.json})
 * @param system  the system part: role, rules, rubric
 * @param user    the task part, with {@code {{placeholders}}}
 */
public record PromptTemplate(String id, String version, String schema, String system, String user) {

    /** {@code {{name}}}: letters, digits and underscores, starting with a letter. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");

    public PromptTemplate {
        Require.notBlank(id, "id", 60);
        if (!id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("prompt id must be lower-case words joined by '-' but was '" + id + "'");
        }
        Require.notBlank(version, "version", 20);
        if (!version.matches("v[1-9][0-9]*")) {
            throw new IllegalArgumentException("prompt version must look like v1 but was '" + version + "'");
        }
        Require.notBlank(schema, "schema", 80);
        Require.notBlank(system, "system");
        Require.notBlank(user, "user");
    }

    /** The placeholders of the system part, in order of appearance. */
    public Set<String> systemPlaceholders() {
        return placeholders(system);
    }

    /** The placeholders of the user part, in order of appearance. */
    public Set<String> userPlaceholders() {
        return placeholders(user);
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
