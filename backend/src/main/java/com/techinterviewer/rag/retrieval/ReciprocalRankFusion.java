package com.techinterviewer.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reciprocal rank fusion: merges ranked lists by summing {@code 1 / (k + rank)} over the lists that contain an item.
 * It needs only ranks, not comparable scores, which is why it suits a vector similarity and a full-text rank that live
 * on different scales. An item found by both halves beats one found by a single half at a similar rank. Ties are
 * broken by the chunk id, so the order is deterministic.
 */
public final class ReciprocalRankFusion {

    /** One fused item: the sum of its reciprocal ranks and its 1-based rank in each list (null when absent). */
    public record Fused(UUID id, double score, Integer[] ranks) {
    }

    private ReciprocalRankFusion() {
    }

    public static List<Fused> fuse(int k, List<List<UUID>> rankings) {
        Map<UUID, Integer[]> ranks = new LinkedHashMap<>();
        for (int list = 0; list < rankings.size(); list++) {
            List<UUID> ranking = rankings.get(list);
            for (int position = 0; position < ranking.size(); position++) {
                Integer[] itemRanks = ranks.computeIfAbsent(ranking.get(position), id -> new Integer[rankings.size()]);
                if (itemRanks[list] == null) {
                    itemRanks[list] = position + 1;
                }
            }
        }
        List<Fused> fused = new ArrayList<>();
        ranks.forEach((id, itemRanks) -> {
            double score = 0;
            for (Integer rank : itemRanks) {
                if (rank != null) {
                    score += 1.0 / (k + rank);
                }
            }
            fused.add(new Fused(id, score, itemRanks));
        });
        fused.sort(Comparator.comparingDouble(Fused::score).reversed().thenComparing(Fused::id));
        return fused;
    }
}
