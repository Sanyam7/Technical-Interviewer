package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The written part of a report, as stored in {@code reports.narrative}. Strengths and improvement areas cite the
 * evaluations they come from; the study plan is a list of next steps.
 *
 * @param summary          a short overall summary
 * @param strengths        what went well
 * @param improvementAreas what to improve
 * @param studyPlan        concrete next steps
 * @param furtherReading   knowledge-base sections the study plan cites; empty for a narrative written without reference
 *                         material (and for narratives stored before it existed)
 */
public record ReportNarrativeDocument(
        String summary,
        List<NarrativeEntry> strengths,
        List<NarrativeEntry> improvementAreas,
        List<String> studyPlan,
        @Nullable List<ReadingEntry> furtherReading) {

    public static final int MAX_SUMMARY_LENGTH = 2000;
    public static final int MAX_ITEMS = 10;
    public static final int MAX_STEP_LENGTH = 500;

    public ReportNarrativeDocument {
        Require.notBlank(summary, "summary", MAX_SUMMARY_LENGTH);
        strengths = Immutables.list(strengths);
        improvementAreas = Immutables.list(improvementAreas);
        studyPlan = Immutables.list(studyPlan);
        Require.between(strengths.size(), 0, MAX_ITEMS, "strengths size");
        Require.between(improvementAreas.size(), 0, MAX_ITEMS, "improvementAreas size");
        Require.between(studyPlan.size(), 0, MAX_ITEMS, "studyPlan size");
        studyPlan.forEach(step -> Require.notBlank(step, "studyPlan entry", MAX_STEP_LENGTH));
        furtherReading = furtherReading == null ? List.of() : Immutables.list(furtherReading);
        Require.between(furtherReading.size(), 0, MAX_ITEMS, "furtherReading size");
    }

    /** A narrative without further reading. */
    public ReportNarrativeDocument(
            String summary, List<NarrativeEntry> strengths, List<NarrativeEntry> improvementAreas, List<String> studyPlan) {
        this(summary, strengths, improvementAreas, studyPlan, List.of());
    }
}
