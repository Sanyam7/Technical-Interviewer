package com.techinterviewer.resume;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A draft profile as the extractor returned it, before verification. Every text field is meant to be copied from the résumé;
 * nothing here is trusted until {@link ResumeProfileVerifier} has checked it against {@code seenText}.
 *
 * @param seenText           the text the model was given: the résumé text with passages that addressed the AI removed
 * @param injectionSuspected the résumé contained such passages
 * @param model              the model that produced the draft
 * @param promptVersion      the prompt version
 * @param llmCallId          the recorded call; null when recording failed
 */
public record ResumeExtraction(
        List<PositionDraft> positions,
        @Nullable StatedYears statedYears,
        List<FactDraft> technologies,
        List<FactDraft> skills,
        List<FactDraft> domains,
        List<ExperienceDraft> experiences,
        String seenText,
        boolean injectionSuspected,
        String model,
        String promptVersion,
        @Nullable UUID llmCallId) {

    /**
     * @param start "YYYY-MM" or "YYYY"
     * @param end   "YYYY-MM", "YYYY", or null or "present" for a current role
     */
    public record PositionDraft(@Nullable String title, @Nullable String organization, @Nullable String start, @Nullable String end,
            @Nullable String evidence) {
    }

    public record StatedYears(int years, @Nullable String evidence) {
    }

    public record FactDraft(@Nullable String name, @Nullable String evidence) {
    }

    /** @param kind PROJECT, RESPONSIBILITY or ACHIEVEMENT */
    public record ExperienceDraft(@Nullable String kind, @Nullable String text, @Nullable String context, List<String> technologies) {

        public ExperienceDraft {
            technologies = technologies == null ? List.of() : technologies.stream().filter(java.util.Objects::nonNull).toList();
        }
    }

    public ResumeExtraction {
        positions = Immutables.list(positions);
        technologies = Immutables.list(technologies);
        skills = Immutables.list(skills);
        domains = Immutables.list(domains);
        experiences = Immutables.list(experiences);
        Require.nonNull(seenText, "seenText");
        Require.notBlank(model, "model", 80);
        Require.notBlank(promptVersion, "promptVersion", 20);
    }
}
