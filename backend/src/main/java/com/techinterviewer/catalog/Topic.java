package com.techinterviewer.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A topic of a skill. Its slug is unique only within the skill ({@code generics} exists for Java and for
 * TypeScript). The skill is referenced by id, not by association: aggregates are loaded through repositories.
 * Read-only for the application, like {@link Skill}.
 */
@Entity
@Table(name = "topics")
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private Long skillId;

    @Column(name = "slug", nullable = false, updatable = false, length = 80)
    private String slug;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description")
    private @Nullable String description;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "min_difficulty", nullable = false)
    private int minDifficulty;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_difficulty", nullable = false)
    private int maxDifficulty;

    @Column(name = "weight", nullable = false, precision = 4, scale = 2)
    private BigDecimal weight;

    protected Topic() {
    }

    public Long getId() {
        return id;
    }

    public Long getSkillId() {
        return skillId;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public int getMinDifficulty() {
        return minDifficulty;
    }

    public int getMaxDifficulty() {
        return maxDifficulty;
    }

    public BigDecimal getWeight() {
        return weight;
    }
}
