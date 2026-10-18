package com.techinterviewer.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Heading-aware chunking: paths, code blocks, size bounds and overlap. */
class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(100, 20);

    @Test
    void everySectionBecomesAChunkWithItsHeadingPath() {
        List<Chunk> chunks = chunker.chunk("Java concurrency", """
                Intro before any heading.

                ## Race conditions

                count++ is not atomic.

                ### Fixes

                Use AtomicInteger.

                ## Volatile

                Visibility, not atomicity.
                """);

        assertThat(chunks).extracting(Chunk::headingPath).containsExactly(
                "Java concurrency",
                "Java concurrency › Race conditions",
                "Java concurrency › Race conditions › Fixes",
                "Java concurrency › Volatile");
        assertThat(chunks).extracting(Chunk::content)
                .containsExactly("Intro before any heading.", "count++ is not atomic.", "Use AtomicInteger.", "Visibility, not atomicity.");
        assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1, 2, 3);
        assertThat(chunks.get(2).embeddingText()).isEqualTo("Java concurrency › Race conditions › Fixes\n\nUse AtomicInteger.");
    }

    @Test
    void aTopLevelHeadingRestartsThePathAndEmptySectionsAreSkipped() {
        List<Chunk> chunks = chunker.chunk("Doc", "# Doc\n\n## Empty\n\n## Filled\n\ntext\n");

        assertThat(chunks).singleElement().satisfies(chunk -> assertThat(chunk.headingPath()).isEqualTo("Doc › Filled"));
    }

    @Test
    void aCodeBlockIsNeverSplitAndHeadingsInsideItAreCode() {
        String code = "```java\n" + IntStream.range(0, 80).mapToObj(i -> "// ## not a heading " + i).collect(Collectors.joining("\n")) + "\n```";
        List<Chunk> chunks = chunker.chunk("Doc", "## Example\n\nBefore.\n\n" + code + "\n\nAfter.\n");

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.headingPath()).isEqualTo("Doc › Example"));
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.content()).contains(code));
        assertThat(chunks.stream().filter(chunk -> chunk.content().contains("```")).count()).isOne();
    }

    @Test
    void aLongSectionIsSplitWithinTheLimitAndEachContinuationRepeatsTheEndOfThePreviousChunk() {
        String paragraph = IntStream.range(0, 40).mapToObj(i -> "Sentence number " + i + " explains a detail.").collect(Collectors.joining(" "));
        List<Chunk> chunks = chunker.chunk("Doc", "## Long\n\n" + paragraph + "\n");

        assertThat(chunks).hasSizeGreaterThan(3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(100 + 20));
        for (int i = 1; i < chunks.size(); i++) {
            String overlap = chunks.get(i).content().split("\n\n")[0];
            assertThat(chunks.get(i - 1).content()).as("chunk " + i + " starts with the end of chunk " + (i - 1)).endsWith(overlap);
            assertThat(overlap.length()).as("the overlap is a few sentences, not the whole chunk").isLessThanOrEqualTo(20 * 4);
        }
        String all = chunks.stream().map(Chunk::content).collect(Collectors.joining(" "));
        assertThat(all).contains("Sentence number 0 ").contains("Sentence number 39 ");
    }

    @Test
    void anUnclosedCodeFenceIsKeptAsCode() {
        List<Chunk> chunks = chunker.chunk("Doc", "## S\n\n```\ncode without end\n");

        assertThat(chunks).singleElement().satisfies(chunk -> assertThat(chunk.content()).isEqualTo("```\ncode without end"));
    }

    @Test
    void aSentenceLongerThanTheLimitIsCut() {
        List<Chunk> chunks = chunker.chunk("Doc", "## S\n\n" + "x".repeat(1000) + "\n");

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(120));
    }

    @Test
    void aDocumentWithoutTextHasNoChunks() {
        assertThat(chunker.chunk("Doc", "## Only\n\n### Headings\n")).isEmpty();
    }

    @Test
    void theLimitsMustLeaveRoomForContent() {
        assertThatThrownBy(() -> new MarkdownChunker(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarkdownChunker(100, 60)).isInstanceOf(IllegalArgumentException.class);
    }
}
