package com.techinterviewer.config;

/** The database password is absent, blank or still an unresolved {@code ${...}} placeholder. */
final class MissingDatabasePasswordException extends RuntimeException {

    static final String ACTION =
            "Copy .env.example to .env in the repository root and run the backend from backend/.";

    MissingDatabasePasswordException() {
        super("POSTGRES_PASSWORD is not set. " + ACTION);
    }
}
