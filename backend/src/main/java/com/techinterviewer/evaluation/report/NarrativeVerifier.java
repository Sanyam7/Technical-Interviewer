package com.techinterviewer.evaluation.report;

import com.techinterviewer.evaluation.domain.NarrativeEntry;
import com.techinterviewer.evaluation.domain.ReadingEntry;
import com.techinterviewer.evaluation.domain.ReportNarrativeDocument;
import com.techinterviewer.interview.port.NarrativeItem;
import com.techinterviewer.interview.port.ReportNarrative;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Checks the narrator's output before it is stored (AI_ARCHITECTURE.md section 2.8): every strength and improvement
 * area must cite evaluations, and every cited evaluation must belong to this interview. An item that cites none, or
 * cites an evaluation of another interview or one that does not exist, is dropped whole: its claim is not backed.
 * Scores in the text are never used; the report shows the computed numbers only.
 */
final class NarrativeVerifier {

    private NarrativeVerifier() {
    }

    /**
     * @param narrative     what the narrator wrote
     * @param evaluationIds the evaluations of this interview
     * @return the verified narrative and how many items were dropped
     */
    static Result verify(ReportNarrative narrative, Set<UUID> evaluationIds) {
        List<NarrativeEntry> strengths = new ArrayList<>();
        List<NarrativeEntry> improvementAreas = new ArrayList<>();
        int dropped = keep(narrative.strengths(), evaluationIds, strengths) + keep(narrative.improvementAreas(), evaluationIds, improvementAreas);
        return new Result(
                new ReportNarrativeDocument(narrative.summary(), strengths, improvementAreas, narrative.studyPlan(),
                        narrative.furtherReading().stream()
                                .map(reference -> new ReadingEntry(reference.chunkId(), reference.title(), reference.section(), reference.source()))
                                .toList()),
                dropped);
    }

    private static int keep(List<NarrativeItem> items, Set<UUID> evaluationIds, List<NarrativeEntry> kept) {
        int dropped = 0;
        for (NarrativeItem item : items) {
            if (item.evaluationIds().isEmpty() || !evaluationIds.containsAll(item.evaluationIds())) {
                dropped++;
            } else {
                kept.add(new NarrativeEntry(item.text(), item.evaluationIds()));
            }
        }
        return dropped;
    }

    /** The narrative that may be stored and the number of unsupported items that were left out. */
    record Result(ReportNarrativeDocument document, int dropped) {
    }
}
