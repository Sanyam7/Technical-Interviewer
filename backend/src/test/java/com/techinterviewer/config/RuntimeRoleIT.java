package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The role of {@code db/ops/runtime-grants.sql}, applied to the migrated schema and used for real (SECURITY.md, DB-1): it reads and
 * writes rows, and everything that changes the schema or destroys a table is refused by the database itself.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RuntimeRoleIT {

    @Autowired
    DataSource owner;

    @Autowired
    JdbcClient jdbc;

    private Connection connectAs(String role, String password) throws SQLException {
        HikariDataSource hikari = (HikariDataSource) owner;
        return DriverManager.getConnection(hikari.getJdbcUrl(), role, password);
    }

    private String grantsFor(String role) throws Exception {
        try (var in = getClass().getResourceAsStream("/db/ops/runtime-grants.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(":\"app_user\"", "\"" + role + "\"");
        }
    }

    @Test
    void theRuntimeRoleReadsAndWritesRowsAndCannotChangeTheSchema() throws Exception {
        String role = "app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.sql("CREATE ROLE \"" + role + "\" LOGIN PASSWORD 'runtime-role-password' NOSUPERUSER NOCREATEDB NOCREATEROLE").update();
        try (Connection admin = owner.getConnection(); Statement statement = admin.createStatement()) {
            statement.execute(grantsFor(role));
            statement.execute(grantsFor(role));
        }

        try (Connection app = connectAs(role, "runtime-role-password"); Statement statement = app.createStatement()) {
            app.setAutoCommit(false);
            // What the application does: read, insert, update and delete rows of its tables.
            statement.execute("INSERT INTO users (id, email, password_hash, display_name, role, enabled, created_at, updated_at) "
                    + "VALUES (gen_random_uuid(), 'role-test@example.com', '{bcrypt}x', 'Role Test', 'CANDIDATE', true, now(), now())");
            assertThat(statement.executeUpdate("UPDATE users SET display_name = 'Changed' WHERE email = 'role-test@example.com'")).isEqualTo(1);
            assertThat(statement.executeUpdate("DELETE FROM users WHERE email = 'role-test@example.com'")).isEqualTo(1);
            app.rollback();
            statement.execute("SELECT count(*) FROM questions");
            statement.execute("SELECT count(*) FROM knowledge_chunks");

            // What a compromised application must not be able to do.
            for (String attack : new String[] {
                "DROP TABLE users CASCADE",
                "ALTER TABLE users ADD COLUMN backdoor text",
                "TRUNCATE TABLE users CASCADE",
                "CREATE TABLE backdoor (id int)",
                "ALTER TABLE users OWNER TO \"" + role + "\"",
                "CREATE ROLE intruder SUPERUSER",
                "ALTER TABLE users DROP CONSTRAINT uq_users_email"}) {
                assertThatThrownBy(() -> statement.execute(attack)).as(attack).isInstanceOf(SQLException.class)
                        .hasMessageMatching("(?s).*(permission denied|must be owner|does not exist).*");
                app.rollback();
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'backdoor'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void theRoleOfTheTestDatabaseIsASuperuserAndTheInspectionSaysSo() {
        RuntimeRoleCheck.Role role = RuntimeRoleCheck.inspect(jdbc);

        assertThat(role.superuser()).isTrue();
        assertThat(role.canCreateInSchema()).isTrue();
    }

    @Test
    void aLimitedRoleIsReportedAsNeitherSuperuserNorAbleToCreate() throws Exception {
        String role = "limited_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.sql("CREATE ROLE \"" + role + "\" LOGIN PASSWORD 'runtime-role-password' NOSUPERUSER NOCREATEDB NOCREATEROLE").update();
        try (Connection admin = owner.getConnection(); Statement statement = admin.createStatement()) {
            statement.execute(grantsFor(role));
        }
        try (Connection app = connectAs(role, "runtime-role-password")) {
            RuntimeRoleCheck.Role inspected = RuntimeRoleCheck.inspect(JdbcClient.create(new org.springframework.jdbc.datasource.SingleConnectionDataSource(app, true)));
            assertThat(inspected).isEqualTo(new RuntimeRoleCheck.Role(role, false, false));
            assertThatThrownBy(() -> RuntimeRoleCheck.judge(new RuntimeRoleCheck.Role("postgres", true, true))).isInstanceOf(IllegalStateException.class);
        }
    }
}
