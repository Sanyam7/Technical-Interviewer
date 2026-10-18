package com.techinterviewer.catalog;

import com.techinterviewer.common.validation.Require;

/** A catalog skill as other features refer to it: the database id to store, the slug and name to show. */
public record SkillRef(long id, String slug, String name) {

    public SkillRef {
        if (id < 1) {
            throw new IllegalArgumentException("id must be at least 1 but was " + id);
        }
        Require.notBlank(slug, "slug", 60);
        Require.notBlank(name, "name", 80);
    }

    static SkillRef of(Skill skill) {
        return new SkillRef(skill.getId(), skill.getSlug(), skill.getName());
    }
}
