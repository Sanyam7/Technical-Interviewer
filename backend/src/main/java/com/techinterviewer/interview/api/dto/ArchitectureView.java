package com.techinterviewer.interview.api.dto;

import com.techinterviewer.common.validation.SingleLineText;
import com.techinterviewer.interview.domain.ArchitectureDiagram;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An architecture diagram of a system design answer, as sent with the answer and returned in the transcript. The server checks its
 * shape (sizes, unique ids and names, connections between existing components), never its content.
 */
@Schema(description = "An architecture diagram: components and the directed connections between them. At most 24 components and 48 "
        + "connections; component ids and names are unique, a connection joins two different existing components, at most one per "
        + "ordered pair.")
public record ArchitectureView(
        @NotNull @Size(min = 1, max = ArchitectureDiagram.MAX_COMPONENTS) List<@NotNull @Valid ComponentView> components,
        @NotNull @Size(max = ArchitectureDiagram.MAX_CONNECTIONS) List<@NotNull @Valid ConnectionView> connections) {

    @Schema(description = "One box of the diagram.")
    public record ComponentView(
            @Schema(description = "Identifier within the diagram, referenced by connections.", example = "orders-db")
            @NotBlank @Pattern(regexp = "[a-z0-9-]{1,20}") String id,
            @NotNull ArchitectureDiagram.Kind kind,
            @Schema(example = "Orders database (PostgreSQL)") @NotBlank @Size(max = ArchitectureDiagram.MAX_NAME_LENGTH) @SingleLineText String name,
            @Schema(description = "What it does or holds.", nullable = true, example = "orders and their state; primary + 2 replicas")
            @Size(max = ArchitectureDiagram.MAX_NOTES_LENGTH) @SingleLineText @Nullable String notes) {
    }

    @Schema(description = "A directed connection: from calls, sends to or writes to.")
    public record ConnectionView(
            @NotBlank @Pattern(regexp = "[a-z0-9-]{1,20}") String from,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{1,20}") String to,
            @Schema(description = "What flows.", nullable = true, example = "publishes OrderPlaced")
            @Size(max = ArchitectureDiagram.MAX_LABEL_LENGTH) @SingleLineText @Nullable String label,
            @Schema(description = "True when the sender does not wait for the receiver (a message or an event).") boolean async) {
    }

    public static ArchitectureView of(ArchitectureDiagram diagram) {
        return new ArchitectureView(
                diagram.components().stream().map(c -> new ComponentView(c.id(), c.kind(), c.name(), c.notes())).toList(),
                diagram.connections().stream().map(c -> new ConnectionView(c.from(), c.to(), c.label(), c.async())).toList());
    }

    /**
     * @throws IllegalArgumentException when the diagram breaks a rule its fields alone do not show (a duplicate id or name, a
     *                                  connection to a component that does not exist, two connections between the same pair)
     */
    public ArchitectureDiagram toDiagram() {
        return new ArchitectureDiagram(
                components.stream().map(c -> new ArchitectureDiagram.Component(c.id(), c.kind(), c.name(), c.notes())).toList(),
                connections.stream().map(c -> new ArchitectureDiagram.Connection(c.from(), c.to(), c.label(), c.async())).toList());
    }
}
