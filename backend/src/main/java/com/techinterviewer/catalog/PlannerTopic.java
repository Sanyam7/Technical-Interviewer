package com.techinterviewer.catalog;

import com.techinterviewer.common.validation.Require;

/**
 * A topic as the interview planner reads it: which skill it belongs to (a topic slug is only unique within its
 * skill), the difficulty window (1 to 5) it can be asked at and its relative weight when topics are chosen.
 */
public record PlannerTopic(
        String skillSlug,
        String skillName,
        String slug,
        String name,
        int minDifficulty,
        int maxDifficulty,
        double weight) {

    public PlannerTopic {
        Require.notBlank(skillSlug, "skillSlug", 60);
        Require.notBlank(skillName, "skillName", 80);
        Require.notBlank(slug, "slug", 80);
        Require.notBlank(name, "name", 120);
        Require.between(minDifficulty, 1, 5, "minDifficulty");
        Require.between(maxDifficulty, minDifficulty, 5, "maxDifficulty");
        if (!(weight > 0)) {
            throw new IllegalArgumentException("weight must be greater than 0 but was " + weight);
        }
    }

    /** The {@code skill/topic} key used in plans and memory. */
    public String key() {
        return skillSlug + "/" + slug;
    }
}
