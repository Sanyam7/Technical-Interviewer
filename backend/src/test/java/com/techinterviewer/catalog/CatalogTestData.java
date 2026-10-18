package com.techinterviewer.catalog;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Throw-away catalog rows for tests, written straight through SQL (the application never writes the catalog) and
 * removed again by {@link #cleanUp()}, so the seeded v1 catalog is left exactly as the migration made it.
 */
final class CatalogTestData {

    private final JdbcClient jdbc;
    private final List<Long> skillIds = new ArrayList<>();

    CatalogTestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long skill(String slug, String name, boolean active) {
        long id = jdbc.sql("""
                        INSERT INTO skills (slug, name, category, description, active)
                        VALUES (:slug, :name, 'TOOL', 'test skill', :active) RETURNING id""")
                .param("slug", slug)
                .param("name", name)
                .param("active", active)
                .query(Long.class)
                .single();
        skillIds.add(id);
        return id;
    }

    long topic(long skillId, String slug, String name, int minDifficulty, int maxDifficulty, double weight) {
        return jdbc.sql("""
                        INSERT INTO topics (skill_id, slug, name, description, min_difficulty, max_difficulty, weight)
                        VALUES (:skill, :slug, :name, 'test topic', :min, :max, :weight) RETURNING id""")
                .param("skill", skillId)
                .param("slug", slug)
                .param("name", name)
                .param("min", minDifficulty)
                .param("max", maxDifficulty)
                .param("weight", weight)
                .query(Long.class)
                .single();
    }

    void deactivate(long skillId) {
        jdbc.sql("UPDATE skills SET active = false WHERE id = :id").param("id", skillId).update();
    }

    void cleanUp() {
        for (long id : skillIds) {
            jdbc.sql("DELETE FROM candidate_profile_skills WHERE skill_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM topics WHERE skill_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM skills WHERE id = :id").param("id", id).update();
        }
        skillIds.clear();
    }
}
