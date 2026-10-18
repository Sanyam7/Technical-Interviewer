package com.techinterviewer.rag;

import java.util.Arrays;
import java.util.Optional;

/**
 * The technologies the knowledge base covers. Every document names exactly one; retrieval filters by a set of them.
 * The list is wider than the skill catalog: a JavaScript or Docker passage can ground a React or system design
 * question even though neither is a catalog skill.
 */
public enum Technology {
    JAVA("java"),
    SPRING_BOOT("spring-boot"),
    REACT("react"),
    JAVASCRIPT("javascript"),
    TYPESCRIPT("typescript"),
    NODEJS("nodejs"),
    SQL("sql"),
    REST("rest"),
    MICROSERVICES("microservices"),
    DATABASES("databases"),
    SYSTEM_DESIGN("system-design"),
    DATA_STRUCTURES("data-structures"),
    ALGORITHMS("algorithms"),
    DOCKER("docker"),
    CLOUD("cloud"),
    GIT("git");

    private final String slug;

    Technology(String slug) {
        this.slug = slug;
    }

    /** The name used in document front matter and stored in {@code knowledge_documents.metadata.technology}. */
    public String slug() {
        return slug;
    }

    public static Optional<Technology> fromSlug(String slug) {
        return Arrays.stream(values()).filter(technology -> technology.slug.equals(slug)).findFirst();
    }
}
