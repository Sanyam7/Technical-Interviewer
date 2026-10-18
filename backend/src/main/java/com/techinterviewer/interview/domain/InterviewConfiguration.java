package com.techinterviewer.interview.domain;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * What a candidate asked an interview to be: role, seniority, difficulty, length, shape and skills. A configuration
 * is owned by one user, is reusable by several interviews and never changes after it was created (it can only be
 * deleted, and not while an interview uses it: {@code fk_interviews_configuration} has no cascade).
 *
 * <p>The skills are referenced by id ({@code interview_configuration_skills}) and loaded together with the
 * configuration, in batches when many configurations are read at once.
 */
@Entity
@Table(name = "interview_configurations")
public class InterviewConfiguration extends BaseEntity {

    /** The lengths the schema allows ({@code ck_interview_configurations_duration}). */
    public static final Set<Integer> ALLOWED_DURATIONS_MINUTES = Set.of(15, 30, 45, 60, 90);
    public static final int MAX_CODING_LANGUAGE_LENGTH = 20;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false, length = 30)
    private TargetRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "experience_level", nullable = false, updatable = false, length = 20)
    private ExperienceLevel experienceLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, updatable = false, length = 10)
    private InterviewDifficulty difficulty;

    @Column(name = "duration_minutes", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_type", nullable = false, updatable = false, length = 20)
    private InterviewType interviewType;

    @Column(name = "coding_language", updatable = false, length = MAX_CODING_LANGUAGE_LENGTH)
    private @Nullable String codingLanguage;

    @Column(name = "use_resume", nullable = false, updatable = false)
    private boolean useResume;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, updatable = false, length = 20)
    private InterviewStrategy strategy;

    @Column(name = "fundamentals_share", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int fundamentalsShare;

    @Column(name = "practical_share", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int practicalShare;

    @Column(name = "project_share", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int projectShare;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "interview_configuration_skills", joinColumns = @JoinColumn(name = "configuration_id"))
    @Column(name = "skill_id", nullable = false)
    @BatchSize(size = 50)
    private Set<Long> skillIds = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InterviewConfiguration() {
    }

    private InterviewConfiguration(
            UUID ownerUserId,
            TargetRole role,
            ExperienceLevel experienceLevel,
            InterviewDifficulty difficulty,
            int durationMinutes,
            InterviewType interviewType,
            @Nullable String codingLanguage,
            boolean useResume,
            InterviewStrategy strategy,
            StageMix mix,
            Set<Long> skillIds,
            Instant createdAt) {
        this.ownerUserId = ownerUserId;
        this.role = role;
        this.experienceLevel = experienceLevel;
        this.difficulty = difficulty;
        this.durationMinutes = durationMinutes;
        this.interviewType = interviewType;
        this.codingLanguage = codingLanguage;
        this.useResume = useResume;
        this.strategy = strategy;
        this.fundamentalsShare = mix.fundamentals();
        this.practicalShare = mix.practical();
        this.projectShare = mix.project();
        this.skillIds = skillIds;
        this.createdAt = createdAt;
    }

    /** A configuration with the default strategy, fundamentals first. */
    public static InterviewConfiguration create(
            UUID ownerUserId,
            TargetRole role,
            ExperienceLevel experienceLevel,
            InterviewDifficulty difficulty,
            int durationMinutes,
            InterviewType interviewType,
            @Nullable String codingLanguage,
            boolean useResume,
            Collection<Long> skillIds,
            Instant now) {
        return create(ownerUserId, role, experienceLevel, difficulty, durationMinutes, interviewType, codingLanguage, useResume,
                InterviewStrategy.DEFAULT, null, skillIds, now);
    }

    /**
     * Creates a configuration. The combination rules mirror the schema: the duration is one of
     * {@link #ALLOWED_DURATIONS_MINUTES}, and a CODING or FULL_LOOP interview needs a coding language
     * ({@code ck_interview_configurations_coding_language}); the other types may carry one but need none. A preset strategy
     * stores its own stage mix; CUSTOM stores the one given (AI_ARCHITECTURE.md section 2.15).
     *
     * @param customMix the stage mix of a CUSTOM strategy; must be null for a preset
     * @throws IllegalArgumentException when a value is missing or the combination is not allowed
     */
    public static InterviewConfiguration create(
            UUID ownerUserId,
            TargetRole role,
            ExperienceLevel experienceLevel,
            InterviewDifficulty difficulty,
            int durationMinutes,
            InterviewType interviewType,
            @Nullable String codingLanguage,
            boolean useResume,
            InterviewStrategy strategy,
            @Nullable StageMix customMix,
            Collection<Long> skillIds,
            Instant now) {
        Require.nonNull(ownerUserId, "ownerUserId");
        Require.nonNull(strategy, "strategy");
        StageMix mix = strategy.preset();
        if (mix == null) {
            mix = Require.nonNull(customMix, "the stage mix of a CUSTOM strategy");
        } else if (customMix != null) {
            throw new IllegalArgumentException("only a CUSTOM strategy takes a stage mix; " + strategy + " has its own");
        }
        if (strategy.requiresResume() && !useResume) {
            throw new IllegalArgumentException("a " + strategy + " interview is personalised from the résumé (useResume)");
        }
        Require.nonNull(role, "role");
        Require.nonNull(experienceLevel, "experienceLevel");
        Require.nonNull(difficulty, "difficulty");
        Require.nonNull(interviewType, "interviewType");
        Require.nonNull(skillIds, "skillIds");
        Require.nonNull(now, "now");
        if (!ALLOWED_DURATIONS_MINUTES.contains(durationMinutes)) {
            throw new IllegalArgumentException(
                    "durationMinutes must be one of " + new TreeSet<>(ALLOWED_DURATIONS_MINUTES) + " but was " + durationMinutes);
        }
        String language = Require.nullOrNotBlank(codingLanguage, "codingLanguage", MAX_CODING_LANGUAGE_LENGTH);
        if (language == null && requiresCodingLanguage(interviewType)) {
            throw new IllegalArgumentException("codingLanguage is required for a " + interviewType + " interview");
        }
        Set<Long> skills = new LinkedHashSet<>();
        for (Long skillId : skillIds) {
            if (skillId == null || skillId <= 0) {
                throw new IllegalArgumentException("skillIds must contain positive ids but contained " + skillId);
            }
            skills.add(skillId);
        }
        return new InterviewConfiguration(
                ownerUserId, role, experienceLevel, difficulty, durationMinutes, interviewType, language, useResume, strategy, mix, skills, now);
    }

    /** True when interviews of this type contain a coding section and therefore need a language. */
    public static boolean requiresCodingLanguage(InterviewType type) {
        return type == InterviewType.CODING || type == InterviewType.FULL_LOOP;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public TargetRole getRole() {
        return role;
    }

    public ExperienceLevel getExperienceLevel() {
        return experienceLevel;
    }

    public InterviewDifficulty getDifficulty() {
        return difficulty;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public InterviewType getInterviewType() {
        return interviewType;
    }

    public @Nullable String getCodingLanguage() {
        return codingLanguage;
    }

    public boolean isUseResume() {
        return useResume;
    }

    /** How the questioning section divides its questions between the stages. */
    public InterviewStrategy getStrategy() {
        return strategy;
    }

    /** The stage shares the strategy stored: the preset's, or the candidate's for CUSTOM. */
    public StageMix getStageMix() {
        return new StageMix(fundamentalsShare, practicalShare, projectShare);
    }

    /** The ids of the skills the interview covers (a set: no order). */
    public Set<Long> getSkillIds() {
        return Collections.unmodifiableSet(skillIds);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
