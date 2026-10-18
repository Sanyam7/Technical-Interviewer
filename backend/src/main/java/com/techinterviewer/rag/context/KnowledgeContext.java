package com.techinterviewer.rag.context;

import com.techinterviewer.rag.retrieval.RetrievedChunk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Reference material ready for a prompt: the text, and the short reference tokens ({@code r1}, {@code r2}, ...) a model
 * may cite, each mapped to its chunk. Citations are only ever these tokens, never chunk ids: a model copies a short
 * token reliably, and an id it invents cannot match.
 *
 * @param text       what goes into the prompt; a fixed sentence when there is no material
 * @param references the chunks in the order of their tokens
 * @param retrieved  false when retrieval is switched off (the call then records no chunk list at all)
 */
public record KnowledgeContext(String text, List<Reference> references, boolean retrieved) {

    public static final String NO_MATERIAL = "(no reference material was found for this task)";

    /** Retrieval is switched off. */
    public static final KnowledgeContext DISABLED = new KnowledgeContext(NO_MATERIAL, List.of(), false);

    /** Retrieval ran and found nothing usable. */
    public static final KnowledgeContext EMPTY = new KnowledgeContext(NO_MATERIAL, List.of(), true);

    /** A chunk and the token that cites it. */
    public record Reference(String token, RetrievedChunk chunk) {
    }

    public KnowledgeContext {
        references = List.copyOf(references);
    }

    public boolean isEmpty() {
        return references.isEmpty();
    }

    public List<String> tokens() {
        return references.stream().map(Reference::token).toList();
    }

    public List<UUID> chunkIds() {
        return references.stream().map(reference -> reference.chunk().chunkId()).toList();
    }

    /** What a model call records in {@code llm_calls.rag_chunk_ids}: the chunks shown, or null without retrieval. */
    public @Nullable List<UUID> recordedChunkIds() {
        return retrieved ? chunkIds() : null;
    }

    public Optional<RetrievedChunk> chunkOf(String token) {
        return references.stream().filter(reference -> reference.token().equals(token)).map(Reference::chunk).findFirst();
    }
}
