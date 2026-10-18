package com.techinterviewer.ai.provider;

/**
 * The provider refused the content for safety reasons (a blocked prompt, or an answer stopped for safety). The same
 * content would be refused again, so it is never retried. The answer evaluator turns it into
 * {@code AnswerBlockedException}: the answer becomes unassessable, it is not scored zero.
 */
public final class ContentBlockedException extends LlmException {

    public ContentBlockedException(String message) {
        super(message);
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public String code() {
        return "BLOCKED";
    }
}
