package com.techinterviewer.interview.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuestionEmbeddingTest {

    private static float[] vector(float first) {
        float[] vector = new float[QuestionEmbedding.DIMENSIONS];
        vector[0] = first;
        vector[767] = 1e-5f;
        return vector;
    }

    @Test
    void theVectorIsCopiedInAndOutAndComparedByValue() {
        float[] values = vector(0.5f);
        QuestionEmbedding embedding = new QuestionEmbedding("embeddinggemma", values);
        values[0] = 9;
        embedding.vector()[0] = 9;

        assertThat(embedding.vector()[0]).isEqualTo(0.5f);
        assertThat(embedding).isEqualTo(new QuestionEmbedding("embeddinggemma", vector(0.5f)))
                .hasSameHashCodeAs(new QuestionEmbedding("embeddinggemma", vector(0.5f)))
                .isNotEqualTo(new QuestionEmbedding("other-model", vector(0.5f)))
                .isNotEqualTo(new QuestionEmbedding("embeddinggemma", vector(0.25f)));
        assertThat(embedding.toString()).isEqualTo("QuestionEmbedding[model=embeddinggemma, dimensions=768]");
    }

    @Test
    void theLiteralIsPgvectorsTextForm() {
        String literal = new QuestionEmbedding("m", vector(-0.5f)).literal();

        assertThat(literal).startsWith("[-0.5,0.0,").endsWith(",1.0E-5]");
        assertThat(literal.split(",")).hasSize(768);
    }

    @Test
    void onlyAModelAndSevenHundredSixtyEightValuesAreAccepted() {
        assertThatThrownBy(() -> new QuestionEmbedding("m", new float[3])).hasMessageContaining("768");
        assertThatThrownBy(() -> new QuestionEmbedding(" ", vector(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestionEmbedding("m", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
