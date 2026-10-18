package com.techinterviewer.interview.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The deterministic plan of an interview, stored once in {@code interviews.plan} and immutable afterwards
 * (DATABASE_SCHEMA.md section 5). It is fully reproducible from the configuration.
 *
 * <p>Sections are listed in the order they run, which is the declaration order of {@link Section}: a plan
 * cannot put coding before questioning.
 *
 * @param version          schema version of this document (increase it when the shape changes)
 * @param sections         the sections, in running order, without duplicates
 * @param difficulty       difficulty band
 * @param maxFollowUpDepth how many follow-ups may hang below one root question of questioning or coding (0 to 2)
 * @param maxDesignProbes  how many dimension probes may follow a system design problem (0 to {@value #MAX_DESIGN_PROBES});
 *                         a plan stored before design probes existed has none (absent in its JSON)
 * @param resume           how questions are anchored on the candidate's résumé; null when the interview is not personalised
 */
public record InterviewPlan(int version, List<SectionPlan> sections, DifficultyBand difficulty, int maxFollowUpDepth,
        @Nullable Integer maxDesignProbes, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable ResumePlan resume) {

    /** 2: the questioning section plans stages (AI_ARCHITECTURE.md section 2.15); a version 1 plan has none and is asked without them. */
    public static final int CURRENT_VERSION = 2;
    public static final int MAX_FOLLOW_UP_DEPTH = 2;
    /** The deepest a design thread may go; the schema allows a follow-up depth up to this (V12). */
    public static final int MAX_DESIGN_PROBES = 6;
    public static final int DEFAULT_DESIGN_PROBES = 4;
    /** The deepest any follow-up may be: a design thread goes deeper than the others. */
    public static final int MAX_THREAD_DEPTH = MAX_DESIGN_PROBES;

    public InterviewPlan {
        Require.atLeast(version, 1, "version");
        sections = Immutables.list(sections);
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("sections must not be empty");
        }
        Section previous = null;
        for (SectionPlan plan : sections) {
            if (previous != null && plan.section().ordinal() <= previous.ordinal()) {
                throw new IllegalArgumentException("sections must be unique and in running order, but "
                        + plan.section() + " follows " + previous);
            }
            previous = plan.section();
        }
        Require.nonNull(difficulty, "difficulty");
        Require.between(maxFollowUpDepth, 0, MAX_FOLLOW_UP_DEPTH, "maxFollowUpDepth");
        maxDesignProbes = maxDesignProbes == null ? 0 : maxDesignProbes;
        Require.between(maxDesignProbes, 0, MAX_DESIGN_PROBES, "maxDesignProbes");
    }

    /** A plan that is not personalised. */
    public InterviewPlan(int version, List<SectionPlan> sections, DifficultyBand difficulty, int maxFollowUpDepth,
            @Nullable Integer maxDesignProbes) {
        this(version, sections, difficulty, maxFollowUpDepth, maxDesignProbes, null);
    }

    /** A plan with the default number of design probes. */
    public InterviewPlan(int version, List<SectionPlan> sections, DifficultyBand difficulty, int maxFollowUpDepth) {
        this(version, sections, difficulty, maxFollowUpDepth, DEFAULT_DESIGN_PROBES);
    }

    /** The deepest follow-up allowed in {@code section}: the design probes for a design thread, the follow-up depth otherwise. */
    public int maxDepthIn(Section section) {
        return section == Section.SYSTEM_DESIGN ? maxDesignProbes : maxFollowUpDepth;
    }

    public Section firstSection() {
        return sections.getFirst().section();
    }

    public Optional<SectionPlan> section(Section section) {
        return sections.stream().filter(plan -> plan.section() == section).findFirst();
    }

    public boolean hasSection(Section section) {
        return section(section).isPresent();
    }

    /** The section that runs after {@code section}, or empty when it is the last one (or not part of the plan). */
    public Optional<Section> sectionAfter(Section section) {
        for (int i = 0; i < sections.size() - 1; i++) {
            if (sections.get(i).section() == section) {
                return Optional.of(sections.get(i + 1).section());
            }
        }
        return Optional.empty();
    }

    public int totalSlots() {
        return sections.stream().mapToInt(SectionPlan::slots).sum();
    }

    public int totalMinutes() {
        return sections.stream().mapToInt(SectionPlan::minutes).sum();
    }
}
