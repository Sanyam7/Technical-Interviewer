package com.techinterviewer.catalog;

import com.techinterviewer.catalog.dto.OptionsResponse;
import com.techinterviewer.catalog.dto.OptionsResponse.InterviewTypeOption;
import com.techinterviewer.catalog.dto.SkillResponse;
import com.techinterviewer.catalog.dto.TopicResponse;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.common.error.FieldViolation;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.engine.TopicRange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The interview catalog: what a candidate can choose (public endpoints) and what the other features need to
 * validate and plan an interview (skill resolution, topics of the selected skills).
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final SkillRepository skills;
    private final TopicRepository topics;

    private final CodeExecutionInfo execution;

    CatalogService(SkillRepository skills, TopicRepository topics, CodeExecutionInfo execution) {
        this.skills = skills;
        this.topics = topics;
        this.execution = execution;
    }

    /** The configuration choices, derived from the enums and {@link InterviewOptions}. */
    public OptionsResponse options() {
        List<InterviewTypeOption> types = Arrays.stream(InterviewType.values())
                .map(type -> new InterviewTypeOption(type, InterviewOptions.sectionsOf(type)))
                .toList();
        return new OptionsResponse(
                List.of(TargetRole.values()),
                List.of(ExperienceLevel.values()),
                List.of(InterviewDifficulty.values()),
                InterviewOptions.DURATIONS_MINUTES,
                types,
                InterviewOptions.CODING_LANGUAGES,
                execution.executableLanguages(),
                Arrays.stream(InterviewStrategy.values())
                        .map(strategy -> new OptionsResponse.StrategyOption(strategy, strategy.preset() == null ? null
                                : new OptionsResponse.StageShares(strategy.preset().fundamentals(), strategy.preset().practical(),
                                        strategy.preset().project()), strategy.requiresResume()))
                        .toList());
    }

    /** Active skills, by name. */
    public List<SkillResponse> activeSkills() {
        return skills.findByActiveTrueOrderByNameAscIdAsc().stream()
                .map(skill -> new SkillResponse(
                        skill.getId(), skill.getSlug(), skill.getName(), skill.getCategory(), skill.getDescription()))
                .toList();
    }

    /** Topics of an active skill, easiest first and by name within a difficulty. An unknown or inactive skill is a {@code 404 NOT_FOUND}. */
    public List<TopicResponse> topicsOfSkill(String slug) {
        Skill skill = skills.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Skill not found."));
        return topics.findBySkillIdOrderByMinDifficultyAscNameAscIdAsc(skill.getId()).stream()
                .map(topic -> new TopicResponse(
                        topic.getSlug(),
                        topic.getName(),
                        topic.getDescription(),
                        topic.getMinDifficulty(),
                        topic.getMaxDifficulty()))
                .toList();
    }

    /**
     * Resolves skill slugs to active skills, all or nothing. The result has one entry per slug, in the order given
     * (a repeated slug yields a repeated entry: rejecting repeats is up to the caller).
     *
     * @param fieldPath names the request field of the slug at an index (for example {@code i -> "skills[" + i + "]"}),
     *     so that every unknown or inactive slug is reported on its own field
     * @throws FieldValidationException when at least one slug is not an active skill; it lists all of them
     */
    public List<SkillRef> requireActiveSkills(List<String> slugs, IntFunction<String> fieldPath) {
        List<String> wanted = List.copyOf(slugs);
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<String, SkillRef> bySlug = skills.findBySlugInAndActiveTrue(new LinkedHashSet<>(wanted)).stream()
                .map(SkillRef::of)
                .collect(Collectors.toMap(SkillRef::slug, Function.identity()));
        List<FieldViolation> unknown = new ArrayList<>();
        List<SkillRef> resolved = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            SkillRef skill = bySlug.get(wanted.get(i));
            if (skill == null) {
                unknown.add(new FieldViolation(fieldPath.apply(i), "Unknown or inactive skill '" + wanted.get(i) + "'."));
            } else {
                resolved.add(skill);
            }
        }
        if (!unknown.isEmpty()) {
            throw new FieldValidationException(unknown);
        }
        return resolved;
    }

    /** The skills with these ids, active or not (a skill can be retired after it was chosen), by id. */
    public Map<Long, SkillRef> skillsById(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return skills.findAllById(ids).stream().map(SkillRef::of).collect(Collectors.toMap(SkillRef::id, Function.identity()));
    }

    /** The stored topics with the given ids (unknown ids are absent from the result), whether or not their skill is still active. */
    public Map<Long, TopicInfo> topicsById(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Topic> found = topics.findAllById(ids);
        Map<Long, Skill> owners = skills.findAllById(found.stream().map(Topic::getSkillId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Skill::getId, Function.identity()));
        Map<Long, TopicInfo> result = new LinkedHashMap<>();
        for (Topic topic : found) {
            Skill skill = owners.get(topic.getSkillId());
            result.put(topic.getId(), new TopicInfo(topic.getId(), skill.getId(), skill.getSlug(), skill.getName(), topic.getSlug(), topic.getName()));
        }
        return result;
    }

    /** The stored skill with this slug, active or not. */
    public java.util.Optional<SkillRef> findSkill(String slug) {
        return skills.findBySlug(slug).map(SkillRef::of);
    }

    /** The stored topic for a {@code skill/topic} slug pair, if both exist. */
    public java.util.Optional<TopicInfo> findTopic(String skillSlug, String topicSlug) {
        return skills.findBySlug(skillSlug).flatMap(skill -> topics.findBySkillIdAndSlug(skill.getId(), topicSlug)
                .map(topic -> new TopicInfo(topic.getId(), skill.getId(), skill.getSlug(), skill.getName(), topic.getSlug(), topic.getName())));
    }

    /**
     * The difficulty range of every topic named by a {@code skill/topic} key, whether or not its skill is still active (an
     * interview keeps asking the topics it was planned with). A key that names no stored topic is absent from the result.
     */
    public Map<String, TopicRange> topicRanges(Collection<String> keys) {
        Map<String, Set<String>> topicSlugsBySkill = new LinkedHashMap<>();
        for (String key : keys) {
            int slash = key.indexOf('/');
            if (slash > 0 && slash < key.length() - 1) {
                topicSlugsBySkill.computeIfAbsent(key.substring(0, slash), skill -> new LinkedHashSet<>()).add(key.substring(slash + 1));
            }
        }
        if (topicSlugsBySkill.isEmpty()) {
            return Map.of();
        }
        Map<Long, Skill> found = skills.findBySlugIn(topicSlugsBySkill.keySet()).stream()
                .collect(Collectors.toMap(Skill::getId, Function.identity()));
        Map<String, TopicRange> ranges = new LinkedHashMap<>();
        for (Topic topic : topics.findBySkillIdInOrderByMinDifficultyAscNameAscIdAsc(found.keySet())) {
            String skillSlug = found.get(topic.getSkillId()).getSlug();
            if (topicSlugsBySkill.get(skillSlug).contains(topic.getSlug())) {
                ranges.put(skillSlug + "/" + topic.getSlug(), new TopicRange(topic.getMinDifficulty(), topic.getMaxDifficulty()));
            }
        }
        return Map.copyOf(ranges);
    }

    /**
     * The topics of the given skills for the planner: skills in the order given (skills that are unknown or
     * inactive contribute nothing), the topics of a skill easiest first and by name within a difficulty.
     */
    public List<PlannerTopic> topicsForPlanning(Collection<Long> skillIds) {
        if (skillIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Skill> active = skills.findAllById(skillIds).stream()
                .filter(Skill::isActive)
                .collect(Collectors.toMap(Skill::getId, Function.identity()));
        Map<Long, List<Topic>> bySkill = new LinkedHashMap<>();
        for (Topic topic : topics.findBySkillIdInOrderByMinDifficultyAscNameAscIdAsc(active.keySet())) {
            bySkill.computeIfAbsent(topic.getSkillId(), id -> new ArrayList<>()).add(topic);
        }
        List<PlannerTopic> result = new ArrayList<>();
        for (Long skillId : new LinkedHashSet<>(skillIds)) {
            Skill skill = active.get(skillId);
            if (skill != null) {
                for (Topic topic : bySkill.getOrDefault(skillId, List.of())) {
                    result.add(new PlannerTopic(
                            skill.getSlug(),
                            skill.getName(),
                            topic.getSlug(),
                            topic.getName(),
                            topic.getMinDifficulty(),
                            topic.getMaxDifficulty(),
                            topic.getWeight().doubleValue()));
                }
            }
        }
        return List.copyOf(result);
    }
}
