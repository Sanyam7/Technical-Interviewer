package com.techinterviewer.rag.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.retrieval.RetrievedChunk;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Turning retrieved chunks into prompt material: order, deduplication, budget and reference tokens. */
class ContextAssemblerTest {

    private static RetrievedChunk chunk(String heading, String content) {
        return new RetrievedChunk(UUID.randomUUID(), "Doc", "java/doc.md", "java", DocumentType.REFERENCE, "Doc › " + heading,
                content, content.length() / 4, null, 1, null, 0.9, 0.03);
    }

    @Test
    void pinnedChunksComeFirstThenTheSearchesInTurnsEachUnderItsToken() {
        RetrievedChunk pinned = chunk("Pinned", "The pinned passage about locks.");
        RetrievedChunk a1 = chunk("A1", "Cache-aside fills the cache on a miss.");
        RetrievedChunk a2 = chunk("A2", "LRU eviction drops the least recently used entry.");
        RetrievedChunk b1 = chunk("B1", "A dead-letter queue holds poison messages.");

        KnowledgeContext context = ContextAssembler.assemble(List.of(pinned), List.of(List.of(a1, a2), List.of(b1)), 1000, 10);

        assertThat(context.references()).extracting(reference -> reference.chunk()).containsExactly(pinned, a1, b1, a2);
        assertThat(context.tokens()).containsExactly("r1", "r2", "r3", "r4");
        assertThat(context.text()).startsWith("[r1] Doc › Pinned (source: java/doc.md)\nThe pinned passage about locks.\n\n[r2] Doc › A1");
        assertThat(context.chunkOf("r3")).contains(b1);
        assertThat(context.chunkOf("r9")).isEmpty();
        assertThat(context.retrieved()).isTrue();
        assertThat(context.recordedChunkIds()).containsExactly(pinned.chunkId(), a1.chunkId(), b1.chunkId(), a2.chunkId());
    }

    @Test
    void aRepeatedChunkAndANearDuplicateAreLeftOut() {
        RetrievedChunk original = chunk("One", "Optimistic locking checks a version column at write time and reports conflicts.");
        RetrievedChunk overlapping = chunk("Two", "Optimistic locking checks a version column at write time and reports conflicts to callers.");
        RetrievedChunk different = chunk("Three", "Deadlocks are cycles of transactions waiting for each other.");

        KnowledgeContext context = ContextAssembler.assemble(List.of(), List.of(List.of(original, original, overlapping, different)), 1000, 10);

        assertThat(context.references()).extracting(reference -> reference.chunk()).containsExactly(original, different);
    }

    @Test
    void aChunkThatDoesNotFitTheBudgetIsSkippedAndASmallerOneAfterItMayStillFit() {
        RetrievedChunk small = chunk("Small", "Indexes speed reads.");
        RetrievedChunk huge = chunk("Huge", "word ".repeat(400));
        RetrievedChunk smallToo = chunk("Small too", "Vacuum reclaims tuples.");

        KnowledgeContext context = ContextAssembler.assemble(List.of(), List.of(List.of(small, huge, smallToo)), 60, 10);

        assertThat(context.references()).extracting(reference -> reference.chunk()).containsExactly(small, smallToo);
    }

    @Test
    void theChunkLimitIsRespected() {
        List<RetrievedChunk> many = List.of(chunk("1", "alpha one"), chunk("2", "bravo two"), chunk("3", "charlie three"));

        assertThat(ContextAssembler.assemble(List.of(), List.of(many), 1000, 2).references()).hasSize(2);
    }

    @Test
    void nothingRetrievedGivesTheEmptyMaterialWhichStillCountsAsRetrieval() {
        KnowledgeContext context = ContextAssembler.assemble(List.of(), List.of(List.of()), 1000, 5);

        assertThat(context).isSameAs(KnowledgeContext.EMPTY);
        assertThat(context.text()).isEqualTo(KnowledgeContext.NO_MATERIAL);
        assertThat(context.recordedChunkIds()).isEmpty();
        assertThat(KnowledgeContext.DISABLED.recordedChunkIds()).as("retrieval switched off records nothing").isNull();
    }
}
