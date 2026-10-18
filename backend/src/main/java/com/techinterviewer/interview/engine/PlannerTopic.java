package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;

/**
 * A catalog topic as the planner sees it.
 *
 * @param slug          topic slug, unique within its skill
 * @param minDifficulty lowest level the topic can be asked at (1 to 5)
 * @param maxDifficulty highest level (1 to 5, not below {@code minDifficulty})
 * @param weight        relative importance among the topics of its skill, 0.01 to 99.99 (the {@code numeric(4,2)} of the catalog)
 */
public record PlannerTopic(String slug, int minDifficulty, int maxDifficulty, double weight) {

    public static final double MIN_WEIGHT = 0.01;
    public static final double MAX_WEIGHT = 99.99;

    public PlannerTopic {
        Require.notBlank(slug, "slug", 80);
        if (slug.indexOf('/') >= 0) {
            throw new IllegalArgumentException("slug must not contain '/': " + slug);
        }
        new TopicRange(minDifficulty, maxDifficulty);
        Require.between(weight, MIN_WEIGHT, MAX_WEIGHT, "weight");
    }

    public TopicRange range() {
        return new TopicRange(minDifficulty, maxDifficulty);
    }
}
