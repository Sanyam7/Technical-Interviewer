package com.techinterviewer.interview.port;

/**
 * Writes the narrative of a report from the deterministic results. Optional: a report without narrative is a
 * complete report. Blocking, and <b>never called inside a database transaction</b>.
 */
public interface ReportNarrator {

    /**
     * @throws AiUnavailableException when no narrative can be produced; the report is then stored without one
     */
    ReportNarrative narrate(NarrativeRequest request);
}
