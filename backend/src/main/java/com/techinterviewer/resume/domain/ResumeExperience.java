package com.techinterviewer.resume.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A project, responsibility or achievement the résumé states: what a personalised question may be anchored on.
 *
 * @param id           {@code x1}, {@code x2}, ... in résumé order, stable for the life of the résumé
 * @param kind         project, responsibility or achievement
 * @param text         the statement, copied from the résumé ("Built a Spring Boot microservice using Kafka")
 * @param context      where it happened, as the résumé names it (a role and employer, or a project name); null when unknown
 * @param technologies canonical names of the technologies the statement itself names
 */
public record ResumeExperience(String id, ExperienceKind kind, String text, @Nullable String context, List<String> technologies) {

    public static final Pattern ID = Pattern.compile("x[0-9]{1,2}");
    public static final int MIN_WORDS = 4;

    public ResumeExperience {
        Require.notBlank(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("an experience id is x1 to x99 but was '" + id + "'");
        }
        Require.nonNull(kind, "kind");
        Require.notBlank(text, "text", ResumeProfile.MAX_EXCERPT_LENGTH);
        context = Require.nullOrNotBlank(context, "context", ResumeProfile.MAX_CONTEXT_LENGTH);
        technologies = Immutables.list(technologies);
        technologies.forEach(name -> Require.notBlank(name, "technology", ResumeFact.MAX_NAME_LENGTH));
    }
}
