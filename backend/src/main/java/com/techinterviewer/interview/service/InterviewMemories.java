package com.techinterviewer.interview.service;

import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small edits of the interview memory that the use cases make themselves (the engine owns the rest). */
public final class InterviewMemories {

    private InterviewMemories() {
    }

    /**
     * The memory with {@code section} marked as started at {@code at} (no main question answered yet). A section that
     * is in the memory already is left as it is, so starting a section twice cannot reset its clock.
     */
    public static InterviewMemory withSectionStarted(InterviewMemory memory, Section section, Instant at) {
        if (memory.sectionProgress().containsKey(section)) {
            return memory;
        }
        Map<Section, SectionProgress> progress = new LinkedHashMap<>(memory.sectionProgress());
        progress.put(section, new SectionProgress(0, at));
        return memory.withSectionProgress(progress);
    }
}
