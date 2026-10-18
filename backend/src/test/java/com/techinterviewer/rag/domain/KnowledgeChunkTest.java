package com.techinterviewer.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KnowledgeChunkTest {

    private static final UUID DOCUMENT = UuidV7.generate();

    private static KnowledgeChunk chunk(int index, String content, int tokens, Long topic, Integer difficulty) {
        return new KnowledgeChunk(DOCUMENT, index, "Concurrency > Memory model", content, tokens, topic, difficulty, Map.of("k", "v"), Fixtures.NOW);
    }

    @Test
    void aChunkHoldsExactlyWhatItWasCreatedWith() {
        KnowledgeChunk chunk = chunk(3, "volatile ...", 17, 5L, 4);

        assertThat(chunk.getDocumentId()).isEqualTo(DOCUMENT);
        assertThat(chunk.getChunkIndex()).isEqualTo(3);
        assertThat(chunk.getHeadingPath()).isEqualTo("Concurrency > Memory model");
        assertThat(chunk.getContent()).isEqualTo("volatile ...");
        assertThat(chunk.getTokenCount()).isEqualTo(17);
        assertThat(chunk.getTopicId()).isEqualTo(5L);
        assertThat(chunk.getDifficulty()).isEqualTo(4);
        assertThat(chunk.getMetadata()).containsExactly(Map.entry("k", "v"));
        assertThat(chunk.getCreatedAt()).isEqualTo(Fixtures.NOW);
        assertThat(UuidV7.isV7(chunk.getId())).isTrue();
    }

    @Test
    void headingTopicAndDifficultyAreOptional() {
        KnowledgeChunk chunk = new KnowledgeChunk(DOCUMENT, 0, null, "text", 0, null, null, Map.of(), Fixtures.NOW);

        assertThat(chunk.getHeadingPath()).isNull();
        assertThat(chunk.getTopicId()).isNull();
        assertThat(chunk.getDifficulty()).isNull();
        assertThat(chunk.getChunkIndex()).isZero();
        assertThat(chunk.getTokenCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1, 10})
    void theDifficultyIsBetweenOneAndFiveWhenGiven(int difficulty) {
        if (difficulty >= 1 && difficulty <= 5) {
            assertThat(chunk(0, "t", 1, null, difficulty).getDifficulty()).isEqualTo(difficulty);
        } else {
            assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, "t", 1, null, difficulty)).withMessageContaining("difficulty");
        }
    }

    @Test
    void everyDifficultyFromOneToFiveIsAccepted() {
        for (int difficulty = 1; difficulty <= 5; difficulty++) {
            assertThat(chunk(0, "t", 1, null, difficulty).getDifficulty()).isEqualTo(difficulty);
        }
    }

    @Test
    void numbersAndTextsAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(-1, "t", 1, null, null)).withMessageContaining("chunkIndex");
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, "t", -1, null, null)).withMessageContaining("tokenCount");
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, " ", 1, null, null)).withMessageContaining("content");
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, null, 1, null, null)).withMessageContaining("content");
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, "t", 1, 0L, null)).withMessageContaining("topicId");
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeChunk(DOCUMENT, 0, "h".repeat(501), "t", 1, null, null, Map.of(), Fixtures.NOW)).withMessageContaining("headingPath");
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeChunk(DOCUMENT, 0, " ", "t", 1, null, null, Map.of(), Fixtures.NOW)).withMessageContaining("headingPath");
        assertThat(new KnowledgeChunk(DOCUMENT, 0, "h".repeat(500), "t", 1, null, null, Map.of(), Fixtures.NOW).getHeadingPath()).hasSize(500);
    }

    @Test
    void requiredValuesAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeChunk(null, 0, null, "t", 1, null, null, Map.of(), Fixtures.NOW)).withMessageContaining("documentId");
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeChunk(DOCUMENT, 0, null, "t", 1, null, null, null, Fixtures.NOW)).withMessageContaining("metadata");
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeChunk(DOCUMENT, 0, null, "t", 1, null, null, Map.of(), null)).withMessageContaining("now");
    }

    @Test
    void metadataIsCopiedAndCannotBeModified() {
        Map<String, String> given = new HashMap<>(Map.of("a", "1"));
        KnowledgeChunk chunk = new KnowledgeChunk(DOCUMENT, 0, null, "t", 1, null, null, given, Fixtures.NOW);

        given.put("b", "2");

        assertThat(chunk.getMetadata()).containsOnlyKeys("a");
        assertThatThrownBy(() -> chunk.getMetadata().put("c", "3")).isInstanceOf(UnsupportedOperationException.class);
    }
}
