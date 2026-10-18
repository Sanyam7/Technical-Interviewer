package com.techinterviewer.config;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Looks at the database role the application runs as, once, when the production profile starts (SECURITY.md, DB-1). A superuser
 * can read every database of the server and run programs on it, so the application refuses to start as one; a role that can
 * create objects in the schema can ruin it, which is a warning, because a platform's single default role often is the owner
 * (the fix is the role of {@code db/ops/runtime-grants.sql}).
 */
final class RuntimeRoleCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRoleCheck.class);

    /** What matters about the role. */
    record Role(String name, boolean superuser, boolean canCreateInSchema) {
    }

    private final JdbcClient jdbc;

    RuntimeRoleCheck(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    static Role inspect(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT current_user::text AS name,
                       (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS superuser,
                       has_schema_privilege(current_user, 'public', 'CREATE') AS can_create""")
                .query((row, index) -> new Role(row.getString("name"), row.getBoolean("superuser"), row.getBoolean("can_create")))
                .single();
    }

    /** @throws IllegalStateException when the role is a superuser */
    static void judge(Role role) {
        if (role.superuser()) {
            throw new IllegalStateException("The application is connected to the database as the superuser '" + role.name()
                    + "'. Create a role for it with db/ops/runtime-grants.sql and set POSTGRES_APP_USER and POSTGRES_APP_PASSWORD.");
        }
        if (role.canCreateInSchema()) {
            log.warn("The application's database role '{}' can create and alter objects. It only needs to read and write rows: "
                    + "create a role with db/ops/runtime-grants.sql (POSTGRES_APP_USER) and keep this one for migrations.", role.name());
        }
    }

    @Override
    public void run(ApplicationArguments arguments) {
        judge(inspect(jdbc));
    }
}
