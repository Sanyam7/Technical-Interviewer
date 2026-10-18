package com.techinterviewer.interview;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Throw-away catalog rows for interview tests (the application never writes the catalog), removed by {@link #cleanUp()}
 * so the seeded catalog is left as the migration made it.
 */
public final class CatalogRows {

    private final JdbcClient jdbc;
    private final List<Long> skillIds = new ArrayList<>();

    public CatalogRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long skill(String slug, String name, boolean active) {
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

    public long topic(long skillId, String slug, String name, int minDifficulty, int maxDifficulty) {
        return jdbc.sql("""
                        INSERT INTO topics (skill_id, slug, name, description, min_difficulty, max_difficulty)
                        VALUES (:skill, :slug, :name, 'test topic', :min, :max) RETURNING id""")
                .param("skill", skillId)
                .param("slug", slug)
                .param("name", name)
                .param("min", minDifficulty)
                .param("max", maxDifficulty)
                .query(Long.class)
                .single();
    }

    public void deactivate(long skillId) {
        jdbc.sql("UPDATE skills SET active = false WHERE id = :id").param("id", skillId).update();
    }

    /** Deletes the rows; call it after the users (and with them every configuration using the skills) are gone. */
    public void cleanUp() {
        for (long id : skillIds) {
            jdbc.sql("DELETE FROM interview_configuration_skills WHERE skill_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM topics WHERE skill_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM skills WHERE id = :id").param("id", id).update();
        }
        skillIds.clear();
    }
}
