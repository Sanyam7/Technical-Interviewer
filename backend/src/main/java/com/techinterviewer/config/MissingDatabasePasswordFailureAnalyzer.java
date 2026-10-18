package com.techinterviewer.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Replaces the bean-creation stack trace of a missing database password with a two-line explanation. */
final class MissingDatabasePasswordFailureAnalyzer extends AbstractFailureAnalyzer<MissingDatabasePasswordException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingDatabasePasswordException cause) {
        return new FailureAnalysis(
                "The database password is not set: POSTGRES_PASSWORD is missing, blank or an unresolved placeholder.",
                MissingDatabasePasswordException.ACTION,
                cause);
    }
}
