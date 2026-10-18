package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.StageMix;
import com.techinterviewer.interview.domain.TargetRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A configuration request that passed every rule, with its skills resolved to catalog ids. */
record ValidatedConfiguration(
        TargetRole role,
        ExperienceLevel experienceLevel,
        InterviewDifficulty difficulty,
        int durationMinutes,
        InterviewType interviewType,
        @Nullable String codingLanguage,
        boolean useResume,
        List<SkillRef> skills,
        InterviewStrategy strategy,
        @Nullable StageMix customMix) {

    ValidatedConfiguration {
        skills = Immutables.list(skills);
    }

    /** The configuration entity of {@code ownerUserId}. */
    InterviewConfiguration toEntity(UUID ownerUserId, Instant now) {
        return InterviewConfiguration.create(
                ownerUserId,
                role,
                experienceLevel,
                difficulty,
                durationMinutes,
                interviewType,
                codingLanguage,
                useResume,
                strategy,
                customMix,
                skills.stream().map(SkillRef::id).toList(),
                now);
    }
}
