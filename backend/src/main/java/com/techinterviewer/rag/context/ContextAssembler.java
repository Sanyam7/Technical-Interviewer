package com.techinterviewer.rag.context;

import com.techinterviewer.rag.chunking.TokenEstimator;
import com.techinterviewer.rag.retrieval.RetrievedChunk;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns retrieved chunks into the reference material of one prompt:
 * <ol>
 *   <li>pinned chunks first (chunks the caller already knows are relevant, such as those a question's key points
 *       cite), then the ranked lists of one or more searches taken in turns, so every search of a multi-search task
 *       (one per knowledge gap) contributes its best chunks before any contributes its second;
 *   <li>a chunk already chosen, or one whose words largely repeat a chosen chunk (overlapping neighbours), is skipped;
 *   <li>chunks are added while they fit the token budget and the chunk limit; one that does not fit is skipped and a
 *       smaller one after it may still fit;
 *   <li>each chunk is written as {@code [r1] heading path (source: path)} followed by its text.
 * </ol>
 */
public final class ContextAssembler {

    static final double NEAR_DUPLICATE = 0.8;

    private ContextAssembler() {
    }

    public static KnowledgeContext assemble(List<RetrievedChunk> pinned, List<List<RetrievedChunk>> ranked, int tokenBudget, int maxChunks) {
        List<RetrievedChunk> candidates = new ArrayList<>(pinned);
        int longest = ranked.stream().mapToInt(List::size).max().orElse(0);
        for (int position = 0; position < longest; position++) {
            for (List<RetrievedChunk> list : ranked) {
                if (position < list.size()) {
                    candidates.add(list.get(position));
                }
            }
        }
        Set<UUID> chosenIds = new HashSet<>();
        List<Set<String>> chosenWords = new ArrayList<>();
        List<KnowledgeContext.Reference> references = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int used = 0;
        for (RetrievedChunk chunk : candidates) {
            if (references.size() >= maxChunks) {
                break;
            }
            if (chosenIds.contains(chunk.chunkId())) {
                continue;
            }
            Set<String> words = words(chunk.content());
            if (chosenWords.stream().anyMatch(other -> overlap(words, other) >= NEAR_DUPLICATE)) {
                continue;
            }
            String token = "r" + (references.size() + 1);
            String entry = "[" + token + "] " + (chunk.headingPath() == null ? chunk.title() : chunk.headingPath())
                    + (chunk.sourcePath() == null ? "" : " (source: " + chunk.sourcePath() + ")") + "\n" + chunk.content().strip();
            int cost = TokenEstimator.estimate(entry);
            if (used + cost > tokenBudget) {
                continue;
            }
            used += cost;
            chosenIds.add(chunk.chunkId());
            chosenWords.add(words);
            references.add(new KnowledgeContext.Reference(token, chunk));
            text.append(text.isEmpty() ? "" : "\n\n").append(entry);
        }
        return references.isEmpty() ? KnowledgeContext.EMPTY : new KnowledgeContext(text.toString(), references, true);
    }

    private static Set<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() > 2).collect(Collectors.toSet());
    }

    /** Shared words over the words of the smaller text: 1 when one text's words are all in the other. */
    private static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        long shared = smaller.stream().filter(larger::contains).count();
        return (double) shared / smaller.size();
    }
}
