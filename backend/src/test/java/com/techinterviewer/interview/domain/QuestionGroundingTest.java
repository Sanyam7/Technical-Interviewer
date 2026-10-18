package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.techinterviewer.common.id.UuidV7;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionGroundingTest {

    private static final UUID QUESTION = UuidV7.generate();
    private static final UUID CHUNK = UuidV7.generate();

    @Test
    void aGroundingIsIdentifiedByQuestionAndChunk() {
        QuestionGrounding grounding = new QuestionGrounding(QUESTION, CHUNK, 2, 0.0325f);

        assertThat(grounding.getId()).isEqualTo(new QuestionGroundingId(QUESTION, CHUNK));
        assertThat(grounding.getQuestionId()).isEqualTo(QUESTION);
        assertThat(grounding.getChunkId()).isEqualTo(CHUNK);
        assertThat(grounding.getRank()).isEqualTo(2);
        assertThat(grounding.getFusedScore()).isEqualTo(0.0325f);
        assertThat(grounding.isNew()).isTrue();
    }

    @Test
    void groundingsOfTheSameQuestionAndChunkAreEqualWhateverTheirScore() {
        QuestionGrounding first = new QuestionGrounding(QUESTION, CHUNK, 1, 0.5f);
        QuestionGrounding second = new QuestionGrounding(QUESTION, CHUNK, 3, 0.1f);
        QuestionGrounding other = new QuestionGrounding(QUESTION, UuidV7.generate(), 1, 0.5f);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotEqualTo(other).isNotEqualTo(null);
        assertThat(first.toString()).contains("QuestionGrounding").contains(QUESTION.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void theRankStartsAtOne(int rank) {
        assertThatIllegalArgumentException().isThrownBy(() -> new QuestionGrounding(QUESTION, CHUNK, rank, 0.5f)).withMessageContaining("rank");
    }

    @Test
    void theScoreMustBeFinite() {
        assertThatIllegalArgumentException().isThrownBy(() -> new QuestionGrounding(QUESTION, CHUNK, 1, Float.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> new QuestionGrounding(QUESTION, CHUNK, 1, Float.POSITIVE_INFINITY));
        assertThat(new QuestionGrounding(QUESTION, CHUNK, 1, 0f).getFusedScore()).isZero();
    }

    @Test
    void bothIdsAreRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> new QuestionGrounding(null, CHUNK, 1, 0.5f)).withMessageContaining("questionId");
        assertThatIllegalArgumentException().isThrownBy(() -> new QuestionGrounding(QUESTION, null, 1, 0.5f)).withMessageContaining("chunkId");
    }
}
