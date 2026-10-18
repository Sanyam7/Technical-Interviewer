package com.techinterviewer.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * A skill of the interview catalog (a language, a framework, a concept). The catalog is seeded by a Flyway
 * migration and is read-only for the application: nothing here changes a skill.
 */
@Entity
@Table(name = "skills")
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "slug", nullable = false, updatable = false, length = 60)
    private String slug;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private SkillCategory category;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Skill() {
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public SkillCategory getCategory() {
        return category;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }
}
