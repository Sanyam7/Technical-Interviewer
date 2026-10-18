package com.techinterviewer.interview.port;

import com.techinterviewer.common.validation.Require;

/** A catalog topic together with its skill, as the AI layer needs to name it. */
public record TopicRef(String skillSlug, String skillName, String topicSlug, String topicName) {

    public TopicRef {
        Require.notBlank(skillSlug, "skillSlug", 60);
        Require.notBlank(skillName, "skillName", 80);
        Require.notBlank(topicSlug, "topicSlug", 80);
        Require.notBlank(topicName, "topicName", 120);
    }

    /** The {@code skill/topic} key used in plans and memory. */
    public String key() {
        return skillSlug + "/" + topicSlug;
    }
}
