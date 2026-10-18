package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.PlannerTopic;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.engine.PlannerInput;
import com.techinterviewer.interview.engine.PlannerSkill;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the input of the interview planner from a configuration and what the catalog says about its skills. */
final class PlannerInputs {

    private PlannerInputs() {
    }

    /**
     * @param skills          the catalog skills of the configuration, by id (retired ones included)
     * @param topics          the planning topics of the skills that are still active; a retired skill has none
     * @param maxFollowUpDepth follow-ups allowed below one main question
     * @param maxDesignProbes  dimension probes allowed after a design problem
     */
    static PlannerInput of(InterviewConfiguration configuration, Map<Long, SkillRef> skills, Collection<PlannerTopic> topics,
            int maxFollowUpDepth, int maxDesignProbes) {
        Map<String, List<PlannerTopic>> topicsBySkill = topics.stream().collect(Collectors.groupingBy(PlannerTopic::skillSlug));
        List<PlannerSkill> plannerSkills = ConfigurationMapper.skillsOf(configuration, skills).stream()
                .sorted(Comparator.comparing(SkillRef::slug))
                .map(skill -> new PlannerSkill(
                        skill.slug(),
                        topicsBySkill.getOrDefault(skill.slug(), List.of()).stream()
                                .map(topic -> new com.techinterviewer.interview.engine.PlannerTopic(
                                        topic.slug(), topic.minDifficulty(), topic.maxDifficulty(), topic.weight()))
                                .toList()))
                .toList();
        return new PlannerInput(
                configuration.getRole(),
                configuration.getExperienceLevel(),
                configuration.getDifficulty(),
                configuration.getDurationMinutes(),
                configuration.getInterviewType(),
                plannerSkills,
                maxFollowUpDepth,
                maxDesignProbes,
                List.of(),
                configuration.getStrategy(),
                configuration.getStageMix());
    }
}
