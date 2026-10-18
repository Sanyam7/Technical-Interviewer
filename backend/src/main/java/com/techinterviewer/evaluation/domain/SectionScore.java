package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.Section;

/**
 * The score of one interview section in a report ({@code reports.section_scores}).
 *
 * @param section the section
 * @param score   0 to 100
 */
public record SectionScore(Section section, double score) {

    public SectionScore {
        Require.nonNull(section, "section");
        Require.between(score, 0.0, 100.0, "score");
    }
}
