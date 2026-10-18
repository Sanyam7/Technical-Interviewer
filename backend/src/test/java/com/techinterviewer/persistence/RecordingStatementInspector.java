package com.techinterviewer.persistence;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Records every SQL statement Hibernate prepares, so a test can assert what a repository call really sent to the
 * database (for example that saving a new entity is one INSERT and no SELECT). Registered by class name through
 * {@code hibernate.session_factory.statement_inspector}, hence the static state.
 */
public class RecordingStatementInspector implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    public static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }
}
