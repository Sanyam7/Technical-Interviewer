package com.techinterviewer.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired refresh tokens. Rotation and login already clean up the tokens of the user who is active; this
 * covers accounts that stopped logging in, so the table stays bounded by the number of live sessions.
 */
@Component
class RefreshTokenPurge {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenPurge.class);

    private final RefreshTokenService tokens;

    RefreshTokenPurge(@Lazy RefreshTokenService tokens) {
        this.tokens = tokens;
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT6H")
    void purge() {
        try {
            int removed = tokens.purgeExpired();
            if (removed > 0) {
                log.info("Purged {} expired refresh token(s)", removed);
            }
        } catch (RuntimeException failure) {
            log.warn("Purging expired refresh tokens failed; it will be retried", failure);
        }
    }
}
