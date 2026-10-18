package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A skill the candidate selected, with the catalog topics it can be asked about.
 *
 * @param slug   skill slug
 * @param topics the skill's topics (may be empty: a coding or design interview does not draw topic questions)
 */
public record PlannerSkill(String slug, List<PlannerTopic> topics) {

    public PlannerSkill {
        Require.notBlank(slug, "slug", 60);
        if (slug.indexOf('/') >= 0) {
            throw new IllegalArgumentException("slug must not contain '/': " + slug);
        }
        topics = Immutables.list(topics);
        Set<String> seen = new HashSet<>();
        for (PlannerTopic topic : topics) {
            if (!seen.add(topic.slug())) {
                throw new IllegalArgumentException("duplicate topic " + slug + "/" + topic.slug());
            }
        }
    }

    /** The {@code skill/topic} key used in plans and in the interview memory. */
    public String topicKey(PlannerTopic topic) {
        return slug + "/" + topic.slug();
    }
}
