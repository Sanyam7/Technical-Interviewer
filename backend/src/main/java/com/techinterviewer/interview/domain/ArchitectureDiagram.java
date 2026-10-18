package com.techinterviewer.interview.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The architecture a candidate drew for a system design problem: components and the directed connections between them
 * (AI_ARCHITECTURE.md section 2.12). Stored with the answer it was submitted with ({@code answers.architecture}); the
 * latest diagram of a design thread is what the evaluator and the probe writer see, as the text of {@link #describe()}.
 *
 * <p>It is the candidate's own material, like the answer text: the server checks its shape (sizes, unique ids and names,
 * connections between existing components), never its content.
 *
 * @param components  at most {@value #MAX_COMPONENTS}, ids and names unique (names ignoring case)
 * @param connections at most {@value #MAX_CONNECTIONS}, each between two different existing components, one per ordered pair
 */
public record ArchitectureDiagram(List<Component> components, List<Connection> connections) {

    public static final int MAX_COMPONENTS = 24;
    public static final int MAX_CONNECTIONS = 48;
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_NOTES_LENGTH = 160;
    public static final int MAX_LABEL_LENGTH = 60;
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,20}");

    /** What a component is, which also decides where the browser draws it (clients on top, data at the bottom). */
    public enum Kind {
        CLIENT("client"),
        CDN("CDN"),
        LOAD_BALANCER("load balancer"),
        API_GATEWAY("API gateway"),
        SERVICE("service"),
        WORKER("worker"),
        CACHE("cache"),
        QUEUE("queue or stream"),
        DATABASE("database"),
        OBJECT_STORAGE("object storage"),
        SEARCH("search index"),
        EXTERNAL("external system");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param id    stable within the diagram, lower-case letters, digits and '-', at most 20
     * @param kind  what it is
     * @param name  what the candidate calls it, for example "Order service" or "PostgreSQL (orders)"
     * @param notes what it does or holds, optional
     */
    public record Component(String id, Kind kind, String name, @Nullable String notes) {

        public Component {
            requireId(id, "component id");
            Require.nonNull(kind, "kind");
            name = Require.notBlank(name, "component name", MAX_NAME_LENGTH).strip();
            notes = Require.nullOrNotBlank(notes == null || notes.isBlank() ? null : notes.strip(), "component notes", MAX_NOTES_LENGTH);
        }
    }

    /**
     * @param from  id of the component that calls, sends or writes
     * @param to    id of the component that is called, receives or is written to
     * @param label what flows, optional (for example "publishes OrderPlaced")
     * @param async whether the sender does not wait for the receiver (a message, an event)
     */
    public record Connection(String from, String to, @Nullable String label, boolean async) {

        public Connection {
            requireId(from, "connection from");
            requireId(to, "connection to");
            if (from.equals(to)) {
                throw new IllegalArgumentException("a connection joins two different components, not " + from + " to itself");
            }
            label = Require.nullOrNotBlank(label == null || label.isBlank() ? null : label.strip(), "connection label", MAX_LABEL_LENGTH);
        }
    }

    public ArchitectureDiagram {
        components = Immutables.list(components);
        connections = Immutables.list(connections);
        if (components.isEmpty()) {
            throw new IllegalArgumentException("a diagram has at least one component");
        }
        if (components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("a diagram has at most " + MAX_COMPONENTS + " components");
        }
        if (connections.size() > MAX_CONNECTIONS) {
            throw new IllegalArgumentException("a diagram has at most " + MAX_CONNECTIONS + " connections");
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Component component : components) {
            if (!ids.add(component.id())) {
                throw new IllegalArgumentException("component id '" + component.id() + "' is used twice");
            }
            if (!names.add(component.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("component name '" + component.name() + "' is used twice");
            }
        }
        Set<String> pairs = new HashSet<>();
        for (Connection connection : connections) {
            if (!ids.contains(connection.from()) || !ids.contains(connection.to())) {
                throw new IllegalArgumentException("connection " + connection.from() + " -> " + connection.to() + " names a component that does not exist");
            }
            if (!pairs.add(connection.from() + ">" + connection.to())) {
                throw new IllegalArgumentException("there are two connections from " + connection.from() + " to " + connection.to());
            }
        }
    }

    /**
     * The diagram as text, for the evaluator and the probe writer, one component or connection per line, for example
     * {@code - Order service (service): validates and stores orders} and
     * {@code - Order service -> Order events (asynchronous): publishes OrderPlaced}. Its words count as the candidate's.
     */
    public String describe() {
        Map<String, String> names = new HashMap<>();
        StringBuilder text = new StringBuilder("Components:\n");
        for (Component component : components) {
            names.put(component.id(), component.name());
            text.append("- ").append(component.name()).append(" (").append(component.kind().label()).append(')');
            if (component.notes() != null) {
                text.append(": ").append(component.notes());
            }
            text.append('\n');
        }
        text.append("Connections:");
        if (connections.isEmpty()) {
            text.append(" none");
        }
        for (Connection connection : connections) {
            text.append("\n- ").append(names.get(connection.from())).append(" -> ").append(names.get(connection.to()));
            if (connection.async()) {
                text.append(" (asynchronous)");
            }
            if (connection.label() != null) {
                text.append(": ").append(connection.label());
            }
        }
        return text.toString();
    }

    private static void requireId(String id, String what) {
        Require.notBlank(id, what);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException(what + " must be 1 to 20 lower-case letters, digits or '-' but was '" + id + "'");
        }
    }
}
