package com.techinterviewer.resume.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The structured candidate profile extracted from a résumé, after verification: everything in it rests on words of the
 * résumé (AI_ARCHITECTURE.md section 2.13). Stored as {@code candidate_resumes.profile}.
 *
 * @param yearsOfExperience stated or computed from the positions; null when neither is possible
 * @param positions         roles with their dates, as listed
 * @param technologies      languages, frameworks, databases, tools
 * @param skills            abilities the résumé shows (system design, mentoring, API design)
 * @param domains           business domains the candidate worked in (payments, healthcare)
 * @param experiences       projects, responsibilities and achievements, in résumé order: what questions may be anchored on
 * @param droppedItems      items the extractor returned that the résumé does not support, left out
 */
public record ResumeProfile(
        @Nullable YearsOfExperience yearsOfExperience,
        List<ResumePosition> positions,
        List<ResumeFact> technologies,
        List<ResumeFact> skills,
        List<ResumeFact> domains,
        List<ResumeExperience> experiences,
        int droppedItems) {

    public static final int MAX_EXCERPT_LENGTH = 300;
    public static final int MAX_CONTEXT_LENGTH = 120;
    public static final int MAX_POSITIONS = 10;
    public static final int MAX_TECHNOLOGIES = 40;
    public static final int MAX_SKILLS = 20;
    public static final int MAX_DOMAINS = 8;
    public static final int MAX_EXPERIENCES = 24;

    public ResumeProfile {
        positions = Immutables.list(positions);
        technologies = Immutables.list(technologies);
        skills = Immutables.list(skills);
        domains = Immutables.list(domains);
        experiences = Immutables.list(experiences);
        Require.between(positions.size(), 0, MAX_POSITIONS, "positions size");
        Require.between(technologies.size(), 0, MAX_TECHNOLOGIES, "technologies size");
        Require.between(skills.size(), 0, MAX_SKILLS, "skills size");
        Require.between(domains.size(), 0, MAX_DOMAINS, "domains size");
        Require.between(experiences.size(), 0, MAX_EXPERIENCES, "experiences size");
        Require.atLeast(droppedItems, 0, "droppedItems");
        Set<String> ids = new HashSet<>();
        experiences.forEach(experience -> {
            if (!ids.add(experience.id())) {
                throw new IllegalArgumentException("experience id " + experience.id() + " is used twice");
            }
        });
    }

    public Optional<ResumeExperience> experience(String id) {
        return experiences.stream().filter(experience -> experience.id().equals(id)).findFirst();
    }

    /** Whether the profile holds anything an interview can use: a role, a technology or an experience. */
    public boolean hasSubstance() {
        return !positions.isEmpty() || !technologies.isEmpty() || !experiences.isEmpty();
    }
}
