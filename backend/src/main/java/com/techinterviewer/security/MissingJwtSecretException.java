package com.techinterviewer.security;

/** The JWT signing secret is absent, blank, not base64 or too short. The secret itself is never part of the message. */
final class MissingJwtSecretException extends RuntimeException {

    static final String ACTION = "Generate a secret with `openssl rand -base64 48`, set it as JWT_SECRET (in the "
            + "repository's .env file or in the environment) and start again.";

    private final String problem;

    MissingJwtSecretException(String problem) {
        super("JWT_SECRET " + problem + ". " + ACTION);
        this.problem = problem;
    }

    /** What is wrong with the secret, without the secret. */
    String problem() {
        return problem;
    }
}
