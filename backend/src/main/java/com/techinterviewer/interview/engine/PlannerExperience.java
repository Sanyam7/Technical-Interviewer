package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.Map;
import java.util.Set;

/**
 * A résumé experience as the planner sees it: what it is linked to, never what it says (AI_ARCHITECTURE.md section 2.13). The
 * links come from the résumé vocabulary; the planner only decides how to use them.
 *
 * @param id       the experience id in the résumé (x1, x2, ...)
 * @param priority lower comes first among equally strong links: projects 0, achievements 1, responsibilities 2
 * @param topics   {@code skill/topic} to the strength of the link (the number of distinct terms that tie them)
 * @param skills   skill slugs the experience names itself
 */
public record PlannerExperience(String id, int priority, Map<String, Integer> topics, Set<String> skills) {

    public PlannerExperience {
        Require.notBlank(id, "id", 10);
        Require.between(priority, 0, 9, "priority");
        topics = Immutables.map(topics);
        topics.forEach((topic, strength) -> Require.atLeast(strength, 1, "strength of " + topic));
        skills = Set.copyOf(skills);
    }
}
