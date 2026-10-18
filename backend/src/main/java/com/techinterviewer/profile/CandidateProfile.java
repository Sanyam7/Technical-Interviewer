package com.techinterviewer.profile;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * What a candidate says about themselves: a headline, years of experience, a job title and a self rating (1 to 5)
 * for catalog skills. There is exactly one per user. The résumé is not part of it (see the resume package).
 */
@Entity
@Table(name = "candidate_profiles")
public class CandidateProfile extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "headline", length = ProfileLimits.HEADLINE_MAX)
    private @Nullable String headline;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "years_experience")
    private @Nullable Integer yearsExperience;

    @Column(name = "job_title", length = ProfileLimits.JOB_TITLE_MAX)
    private @Nullable String jobTitle;

    /** Skill id to self rating. */
    @ElementCollection
    @CollectionTable(name = "candidate_profile_skills", joinColumns = @JoinColumn(name = "profile_id"))
    @MapKeyColumn(name = "skill_id")
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "self_rating", nullable = false)
    private Map<Long, Integer> skillRatings = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CandidateProfile() {
    }

    /** An empty profile of a user. */
    public CandidateProfile(UUID userId, Instant now) {
        this.userId = Require.nonNull(userId, "userId");
        this.createdAt = Require.nonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Replaces everything the candidate maintains: the three text and number fields and the whole set of rated
     * skills (a skill that is not in {@code ratings} is removed).
     *
     * @param ratings skill id to self rating; at most {@value ProfileLimits#SKILLS_MAX} entries, ratings from 1 to 5
     */
    public void replace(
            @Nullable String headline,
            @Nullable Integer yearsExperience,
            @Nullable String jobTitle,
            Map<Long, Integer> ratings,
            Instant now) {
        String checkedHeadline = headline == null ? null : Require.notBlank(headline, "headline", ProfileLimits.HEADLINE_MAX);
        Integer checkedYears = yearsExperience == null
                ? null
                : Require.between(yearsExperience, 0, ProfileLimits.YEARS_EXPERIENCE_MAX, "yearsExperience");
        String checkedJobTitle = jobTitle == null ? null : Require.notBlank(jobTitle, "jobTitle", ProfileLimits.JOB_TITLE_MAX);
        Require.nonNull(ratings, "ratings");
        if (ratings.size() > ProfileLimits.SKILLS_MAX) {
            throw new IllegalArgumentException("a profile has at most " + ProfileLimits.SKILLS_MAX + " skills but got " + ratings.size());
        }
        ratings.forEach((skillId, rating) -> {
            Require.nonNull(skillId, "skillId");
            Require.between(Require.nonNull(rating, "selfRating"), ProfileLimits.RATING_MIN, ProfileLimits.RATING_MAX, "selfRating");
        });
        Instant checkedNow = Require.nonNull(now, "now");

        this.headline = checkedHeadline;
        this.yearsExperience = checkedYears;
        this.jobTitle = checkedJobTitle;
        this.skillRatings.keySet().retainAll(ratings.keySet());
        this.skillRatings.putAll(ratings);
        this.updatedAt = checkedNow;
    }

    public UUID getUserId() {
        return userId;
    }

    public @Nullable String getHeadline() {
        return headline;
    }

    public @Nullable Integer getYearsExperience() {
        return yearsExperience;
    }

    public @Nullable String getJobTitle() {
        return jobTitle;
    }

    /** Skill id to self rating, read-only. */
    public Map<Long, Integer> getSkillRatings() {
        return Map.copyOf(skillRatings);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
