package com.techinterviewer.profile;

import com.techinterviewer.common.id.UuidV7;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Accounts for profile tests, inserted with SQL (the profile has a foreign key to users) and removed by {@link #cleanUp()}. */
final class ProfileTestUsers {

    private final JdbcClient jdbc;
    private final List<UUID> ids = new ArrayList<>();

    ProfileTestUsers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID create() {
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, display_name, role)
                        VALUES (:id, :email, '{noop}not-a-real-hash', 'Test User', 'CANDIDATE')""")
                .param("id", id)
                .param("email", "profile-" + id + "@example.com")
                .update();
        ids.add(id);
        return id;
    }

    /** Deleting a user cascades to its profile and the rated skills. */
    void cleanUp() {
        ids.forEach(id -> jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update());
        ids.clear();
    }
}
