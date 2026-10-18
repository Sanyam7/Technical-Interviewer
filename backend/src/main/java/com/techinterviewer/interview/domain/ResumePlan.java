package com.techinterviewer.interview.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.Map;

/**
 * How an interview uses the candidate's résumé, decided by the planner (AI_ARCHITECTURE.md section 2.13). Ids are those of the
 * résumé's experiences (x1, x2, ...), never their text: the plan holds no résumé content.
 *
 * @param questions how many questioning questions may be anchored on an experience; each experience is used at most once
 * @param topics    {@code skill/topic} to the experiences linked to that topic, strongest first
 * @param skills    skill slug to the experiences linked to that skill (it names the skill itself, or a topic of it), as a fallback
 */
public record ResumePlan(int questions, Map<String, List<String>> topics, Map<String, List<String>> skills) {

    public ResumePlan {
        Require.between(questions, 1, SectionPlan.MAX_SLOTS, "questions");
        topics = Immutables.map(topics);
        skills = Immutables.map(skills);
        topics.values().forEach(ids -> ids.forEach(id -> Require.notBlank(id, "experience id", 10)));
        skills.values().forEach(ids -> ids.forEach(id -> Require.notBlank(id, "experience id", 10)));
    }
}
