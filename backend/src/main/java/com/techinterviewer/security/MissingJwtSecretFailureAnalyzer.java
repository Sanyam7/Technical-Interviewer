package com.techinterviewer.security;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Replaces the bean-creation stack trace of a missing or unusable JWT secret with a short explanation. */
final class MissingJwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<MissingJwtSecretException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingJwtSecretException cause) {
        return new FailureAnalysis(
                "The JWT signing secret cannot be used: JWT_SECRET " + cause.problem() + ".",
                MissingJwtSecretException.ACTION,
                cause);
    }
}
