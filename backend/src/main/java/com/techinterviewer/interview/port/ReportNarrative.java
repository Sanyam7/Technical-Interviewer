package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The written part of a report. The numbers in it are never trusted or displayed: scores come from the
 * deterministic fields of the report.
 *
 * @param summary          a short overall summary
 * @param strengths        what went well, each item citing evaluations
 * @param improvementAreas what to improve, each item citing evaluations
 * @param studyPlan        concrete next steps
 * @param furtherReading   knowledge-base sections the study plan cites, deduplicated, in citation order
 * @param promptVersion    version of the prompt that wrote it ({@code reports.prompt_version})
 * @param llmCallId        id of the recorded model call ({@code reports.llm_call_id})
 */
public record ReportNarrative(
        String summary,
        List<NarrativeItem> strengths,
        List<NarrativeItem> improvementAreas,
        List<String> studyPlan,
        List<StudyReference> furtherReading,
        @Nullable String promptVersion,
        @Nullable UUID llmCallId) {

    public static final int MAX_SUMMARY_LENGTH = 2000;
    public static final int MAX_ITEMS = 10;

    public ReportNarrative {
        Require.notBlank(summary, "summary", MAX_SUMMARY_LENGTH);
        strengths = Immutables.list(strengths);
        improvementAreas = Immutables.list(improvementAreas);
        studyPlan = Immutables.list(studyPlan);
        Require.between(strengths.size(), 0, MAX_ITEMS, "strengths size");
        Require.between(improvementAreas.size(), 0, MAX_ITEMS, "improvementAreas size");
        Require.between(studyPlan.size(), 0, MAX_ITEMS, "studyPlan size");
        studyPlan.forEach(step -> Require.notBlank(step, "studyPlan entry", 500));
        furtherReading = Immutables.list(furtherReading);
        Require.between(furtherReading.size(), 0, MAX_ITEMS, "furtherReading size");
        promptVersion = Require.nullOrNotBlank(promptVersion, "promptVersion", 20);
    }

    /** A narrative without further reading (written without reference material). */
    public ReportNarrative(
            String summary,
            List<NarrativeItem> strengths,
            List<NarrativeItem> improvementAreas,
            List<String> studyPlan,
            @Nullable String promptVersion,
            @Nullable UUID llmCallId) {
        this(summary, strengths, improvementAreas, studyPlan, List.of(), promptVersion, llmCallId);
    }
}
