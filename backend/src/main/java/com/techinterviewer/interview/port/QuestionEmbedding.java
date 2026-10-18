package com.techinterviewer.interview.port;

import com.techinterviewer.common.validation.Require;
import java.util.Arrays;

/**
 * The vector of a generated question, stored in {@code questions.embedding} with the model that produced it, so later
 * questions of the interview can be compared with it. Vectors of different models are never compared.
 *
 * @param model  the embedding model
 * @param vector unit-length vector of 768 values
 */
public record QuestionEmbedding(String model, float[] vector) {

    public static final int DIMENSIONS = 768;

    public QuestionEmbedding {
        Require.notBlank(model, "model", 80);
        Require.nonNull(vector, "vector");
        if (vector.length != DIMENSIONS) {
            throw new IllegalArgumentException("a question embedding has " + DIMENSIONS + " values but this one has " + vector.length);
        }
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }

    /** The vector in pgvector's text form, {@code [0.1,0.2,...]}. */
    public String literal() {
        StringBuilder text = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QuestionEmbedding that && model.equals(that.model) && Arrays.equals(vector, that.vector);
    }

    @Override
    public int hashCode() {
        return 31 * model.hashCode() + Arrays.hashCode(vector);
    }

    @Override
    public String toString() {
        return "QuestionEmbedding[model=" + model + ", dimensions=" + vector.length + "]";
    }
}
