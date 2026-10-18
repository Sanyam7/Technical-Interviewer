package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;
import java.time.Instant;

/**
 * Progress of one section.
 *
 * @param slotsUsed root questions asked so far
 * @param startedAt when the first question of the section was asked (drives the soft time budget)
 */
public record SectionProgress(int slotsUsed, Instant startedAt) {

    public SectionProgress {
        Require.atLeast(slotsUsed, 0, "slotsUsed");
        Require.nonNull(startedAt, "startedAt");
    }
}
