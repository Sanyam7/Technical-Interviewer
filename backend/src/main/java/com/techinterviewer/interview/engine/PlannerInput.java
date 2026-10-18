package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.StageMix;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the planner needs; the plan is a pure function of it (the order of {@code skills} does not matter).
 *
 * @param role             role interviewed for
 * @param experience       seniority of the candidate
 * @param difficulty       difficulty the candidate asked for
 * @param durationMinutes  length of the interview, 15 to 180 (the configuration allows 15, 30, 45, 60 and 90)
 * @param type             interview type
 * @param skills           the selected skills with their topics; at least one
 * @param maxFollowUpDepth follow-ups allowed below one root question (0 to 2), from {@code app.interview.max-follow-up-depth}
 * @param maxDesignProbes  dimension probes allowed after a design problem (0 to 6), from {@code app.interview.max-design-probes}
 * @param experiences      the résumé experiences linked to the selected skills' topics; empty when the interview is not personalised
 * @param strategy         how the questioning section divides its questions between the stages (AI_ARCHITECTURE.md section 2.15)
 * @param mix              the stage shares of the strategy: the preset's, or the candidate's for CUSTOM
 */
public record PlannerInput(
        TargetRole role,
        ExperienceLevel experience,
        InterviewDifficulty difficulty,
        int durationMinutes,
        InterviewType type,
        List<PlannerSkill> skills,
        int maxFollowUpDepth,
        int maxDesignProbes,
        List<PlannerExperience> experiences,
        InterviewStrategy strategy,
        StageMix mix) {

    public static final int MIN_DURATION_MINUTES = 15;
    public static final int MAX_DURATION_MINUTES = 180;

    public PlannerInput {
        Require.nonNull(role, "role");
        Require.nonNull(experience, "experience");
        Require.nonNull(difficulty, "difficulty");
        Require.between(durationMinutes, MIN_DURATION_MINUTES, MAX_DURATION_MINUTES, "durationMinutes");
        Require.nonNull(type, "type");
        skills = Immutables.list(skills);
        if (skills.isEmpty()) {
            throw new IllegalArgumentException("skills must not be empty");
        }
        Set<String> slugs = new HashSet<>();
        for (PlannerSkill skill : skills) {
            if (!slugs.add(skill.slug())) {
                throw new IllegalArgumentException("duplicate skill " + skill.slug());
            }
        }
        Require.between(maxFollowUpDepth, 0, InterviewPlan.MAX_FOLLOW_UP_DEPTH, "maxFollowUpDepth");
        Require.between(maxDesignProbes, 0, InterviewPlan.MAX_DESIGN_PROBES, "maxDesignProbes");
        Require.nonNull(strategy, "strategy");
        Require.nonNull(mix, "mix");
        experiences = Immutables.list(experiences);
        Set<String> ids = new HashSet<>();
        for (PlannerExperience item : experiences) {
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("duplicate experience " + item.id());
            }
        }
    }

    /** An input with the default strategy, fundamentals first. */
    public PlannerInput(TargetRole role, ExperienceLevel experience, InterviewDifficulty difficulty, int durationMinutes, InterviewType type,
            List<PlannerSkill> skills, int maxFollowUpDepth, int maxDesignProbes, List<PlannerExperience> experiences) {
        this(role, experience, difficulty, durationMinutes, type, skills, maxFollowUpDepth, maxDesignProbes, experiences,
                InterviewStrategy.DEFAULT, InterviewStrategy.DEFAULT.preset());
    }

    /** The same input with {@code experiences} from the candidate's résumé. */
    public PlannerInput withExperiences(List<PlannerExperience> resumeExperiences) {
        return new PlannerInput(role, experience, difficulty, durationMinutes, type, skills, maxFollowUpDepth, maxDesignProbes, resumeExperiences,
                strategy, mix);
    }

    /** An input without a résumé. */
    public PlannerInput(TargetRole role, ExperienceLevel experience, InterviewDifficulty difficulty, int durationMinutes, InterviewType type,
            List<PlannerSkill> skills, int maxFollowUpDepth, int maxDesignProbes) {
        this(role, experience, difficulty, durationMinutes, type, skills, maxFollowUpDepth, maxDesignProbes, List.of());
    }

    /** An input with the default number of design probes. */
    public PlannerInput(TargetRole role, ExperienceLevel experience, InterviewDifficulty difficulty, int durationMinutes, InterviewType type,
            List<PlannerSkill> skills, int maxFollowUpDepth) {
        this(role, experience, difficulty, durationMinutes, type, skills, maxFollowUpDepth, InterviewPlan.DEFAULT_DESIGN_PROBES);
    }

    /** The difficulty range of every topic of every selected skill, by {@code skill/topic} key. */
    public Map<String, TopicRange> topicRanges() {
        Map<String, TopicRange> ranges = new LinkedHashMap<>();
        for (PlannerSkill skill : skills) {
            for (PlannerTopic topic : skill.topics()) {
                ranges.put(skill.topicKey(topic), topic.range());
            }
        }
        return Map.copyOf(ranges);
    }
}
