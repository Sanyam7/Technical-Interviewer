package com.techinterviewer.interview.port;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Confidence;

/** A computed per-skill score handed to the report narrator as a fact to describe, not to recompute. */
public record SkillScoreSummary(String skillSlug, String skillName, double score, Confidence confidence, int evidenceCount) {

    public SkillScoreSummary {
        Require.notBlank(skillSlug, "skillSlug", 60);
        Require.notBlank(skillName, "skillName", 80);
        Require.between(score, 0, 100, "score");
        Require.nonNull(confidence, "confidence");
        Require.atLeast(evidenceCount, 0, "evidenceCount");
    }
}
