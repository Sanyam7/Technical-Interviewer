package com.techinterviewer.catalog;

import com.techinterviewer.common.validation.Require;

/**
 * A stored topic as other features show and resolve it: the topic id kept on questions, and the skill and topic
 * names and slugs for display. Unlike {@link PlannerTopic} it does not depend on the skill being active, so an
 * interview taken before a skill was retired still reads correctly.
 */
public record TopicInfo(long id, long skillId, String skillSlug, String skillName, String slug, String name) {

    public TopicInfo {
        if (id < 1) {
            throw new IllegalArgumentException("id must be at least 1 but was " + id);
        }
        if (skillId < 1) {
            throw new IllegalArgumentException("skillId must be at least 1 but was " + skillId);
        }
        Require.notBlank(skillSlug, "skillSlug", 60);
        Require.notBlank(skillName, "skillName", 80);
        Require.notBlank(slug, "slug", 80);
        Require.notBlank(name, "name", 120);
    }

    /** The {@code skill/topic} key used in plans and memory. */
    public String key() {
        return skillSlug + "/" + slug;
    }
}
