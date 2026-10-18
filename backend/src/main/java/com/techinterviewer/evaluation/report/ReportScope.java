package com.techinterviewer.evaluation.report;

import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.AssessmentAreas.Subject;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the interview meant to assess, from its plan and configuration (AI_ARCHITECTURE.md section 2.15): the report says which of it
 * was not assessed.
 *
 * @param plannedAreas  the assessment areas the plan has questions for
 * @param skills        the selected skills when the interview asks topic questions (a coding or design interview asks none)
 * @param plannedTopics the topics the plan's quotas intended to ask
 */
record ReportScope(Set<AssessmentArea> plannedAreas, List<Subject> skills, List<Subject> plannedTopics) {

    /** A scope that plans nothing in particular: no area is reported as missed, no skill or topic as unassessed. */
    static final ReportScope NONE = new ReportScope(Set.of(), List.of(), List.of());

    ReportScope {
        plannedAreas = plannedAreas.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(plannedAreas));
        skills = List.copyOf(skills);
        plannedTopics = List.copyOf(plannedTopics);
    }
}
