package com.techinterviewer.rag.chunking;

/**
 * A provider-independent token estimate: about four characters per token for English technical text. Used to size
 * chunks and to fit retrieved context into a prompt budget, where a consistent estimate matters more than an exact
 * count (the providers' tokenizers differ anyway).
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        return (text.length() + 3) / 4;
    }
}
