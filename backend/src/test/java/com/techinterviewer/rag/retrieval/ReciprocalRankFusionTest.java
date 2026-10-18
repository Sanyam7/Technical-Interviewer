package com.techinterviewer.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void anItemFoundByBothListsBeatsItemsFoundByOne() {
        List<ReciprocalRankFusion.Fused> fused = ReciprocalRankFusion.fuse(60, List.of(List.of(A, B), List.of(C, B)));

        assertThat(fused).extracting(ReciprocalRankFusion.Fused::id).containsExactly(B, A, C);
        assertThat(fused.getFirst().score()).isCloseTo(2.0 / 62, within(1e-12));
        assertThat(fused.getFirst().ranks()).containsExactly(2, 2);
        assertThat(fused.get(1).ranks()).containsExactly(1, null);
    }

    @Test
    void tiesAreBrokenByIdSoTheOrderIsDeterministic() {
        assertThat(ReciprocalRankFusion.fuse(60, List.of(List.of(C), List.of(A))))
                .extracting(ReciprocalRankFusion.Fused::id).containsExactly(A, C);
    }

    @Test
    void emptyListsFuseToNothing() {
        assertThat(ReciprocalRankFusion.fuse(60, List.of(List.of(), List.of()))).isEmpty();
    }
}
