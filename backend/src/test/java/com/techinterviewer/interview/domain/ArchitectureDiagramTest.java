package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.ArchitectureDiagram.Component;
import com.techinterviewer.interview.domain.ArchitectureDiagram.Connection;
import com.techinterviewer.interview.domain.ArchitectureDiagram.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The architecture diagram of a design answer: its shape is checked, its content is the candidate's. */
class ArchitectureDiagramTest {

    private static final Component API = new Component("api", Kind.SERVICE, "Redirect service", "stateless, autoscaled");
    private static final Component CACHE = new Component("cache", Kind.CACHE, "Link cache", null);
    private static final Component EVENTS = new Component("events", Kind.QUEUE, "Click events", "  ");

    @Test
    void itDescribesItselfAsTextTheEvaluatorReadsAndCanQuote() {
        ArchitectureDiagram diagram = new ArchitectureDiagram(List.of(API, CACHE, EVENTS), List.of(
                new Connection("api", "cache", "read-through by short code", false),
                new Connection("api", "events", "publishes Clicked", true)));

        assertThat(diagram.describe()).isEqualTo("""
                Components:
                - Redirect service (service): stateless, autoscaled
                - Link cache (cache)
                - Click events (queue or stream)
                Connections:
                - Redirect service -> Link cache: read-through by short code
                - Redirect service -> Click events (asynchronous): publishes Clicked""");
        assertThat(new ArchitectureDiagram(List.of(API), List.of()).describe()).endsWith("Connections: none");
    }

    @Test
    void theShapeIsChecked() {
        assertThatThrownBy(() -> new ArchitectureDiagram(List.of(), List.of())).hasMessageContaining("at least one component");
        assertThatThrownBy(() -> new ArchitectureDiagram(List.of(API, new Component("api", Kind.CACHE, "Other", null)), List.of()))
                .hasMessageContaining("'api' is used twice");
        assertThatThrownBy(() -> new ArchitectureDiagram(List.of(API, new Component("api2", Kind.CACHE, "redirect SERVICE", null)), List.of()))
                .hasMessageContaining("name 'redirect SERVICE' is used twice");
        assertThatThrownBy(() -> new ArchitectureDiagram(List.of(API), List.of(new Connection("api", "db", null, false))))
                .hasMessageContaining("does not exist");
        assertThatThrownBy(() -> new Connection("api", "api", null, false)).hasMessageContaining("itself");
        assertThatThrownBy(() -> new ArchitectureDiagram(List.of(API, CACHE),
                List.of(new Connection("api", "cache", "a", false), new Connection("api", "cache", "b", true)))).hasMessageContaining("two connections");
        assertThatThrownBy(() -> new Component("API", Kind.SERVICE, "x", null)).hasMessageContaining("lower-case");
        assertThatThrownBy(() -> new Component("a", Kind.SERVICE, "x".repeat(41), null)).hasMessageContaining("name");
        List<Component> many = new ArrayList<>();
        for (int i = 0; i <= ArchitectureDiagram.MAX_COMPONENTS; i++) {
            many.add(new Component("c" + i, Kind.SERVICE, "Service " + i, null));
        }
        assertThatThrownBy(() -> new ArchitectureDiagram(many, List.of())).hasMessageContaining("at most 24 components");
        assertThat(EVENTS.notes()).as("blank notes are none").isNull();
    }
}
