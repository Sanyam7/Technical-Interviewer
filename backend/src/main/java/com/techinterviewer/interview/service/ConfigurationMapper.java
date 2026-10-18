package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.interview.api.dto.ConfigurationResponse;
import com.techinterviewer.interview.api.dto.StageMixView;
import com.techinterviewer.interview.api.dto.SkillSummary;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Maps a configuration entity to its response. */
public final class ConfigurationMapper {

    private ConfigurationMapper() {
    }

    /**
     * @param skills the catalog skills by id; it must contain every skill of the configuration. The response lists the
     *               skills by name (then slug): a configuration stores a set of skills, not the order they were chosen in
     */
    public static ConfigurationResponse toResponse(InterviewConfiguration configuration, Map<Long, SkillRef> skills) {
        return new ConfigurationResponse(
                configuration.getId(),
                configuration.getRole(),
                configuration.getExperienceLevel(),
                configuration.getDifficulty(),
                configuration.getDurationMinutes(),
                configuration.getInterviewType(),
                skillsOf(configuration, skills).stream().map(skill -> new SkillSummary(skill.slug(), skill.name())).toList(),
                configuration.getCodingLanguage(),
                configuration.isUseResume(),
                configuration.getStrategy(),
                new StageMixView(configuration.getStageMix().fundamentals(), configuration.getStageMix().practical(),
                        configuration.getStageMix().project()),
                configuration.getCreatedAt());
    }

    /** The skills of the configuration, by name then slug. */
    static List<SkillRef> skillsOf(InterviewConfiguration configuration, Map<Long, SkillRef> skills) {
        return configuration.getSkillIds().stream()
                .map(id -> {
                    SkillRef skill = skills.get(id);
                    if (skill == null) {
                        throw new IllegalStateException("skill " + id + " of configuration " + configuration.getId() + " is not in the catalog");
                    }
                    return skill;
                })
                .sorted(Comparator.comparing(SkillRef::name).thenComparing(SkillRef::slug))
                .toList();
    }
}
