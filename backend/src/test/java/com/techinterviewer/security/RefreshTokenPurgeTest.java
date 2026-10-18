package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class RefreshTokenPurgeTest {

    private final RefreshTokenService tokens = mock(RefreshTokenService.class);
    private final RefreshTokenPurge purge = new RefreshTokenPurge(tokens);

    @Test
    void purgeDelegatesToTheService() {
        when(tokens.purgeExpired()).thenReturn(3);

        purge.purge();

        verify(tokens, times(1)).purgeExpired();
    }

    @Test
    void aFailingPurgeIsSwallowedSoTheScheduleKeepsRunning() {
        when(tokens.purgeExpired()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(purge::purge).doesNotThrowAnyException();
        verify(tokens, times(1)).purgeExpired();
    }
}
