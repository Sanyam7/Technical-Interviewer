package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.techinterviewer.TestAppProperties;
import com.techinterviewer.TestClock;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.security.dto.LoginRequest;
import com.techinterviewer.security.dto.RegisterRequest;
import com.techinterviewer.user.Role;
import com.techinterviewer.user.User;
import com.techinterviewer.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

class AuthServiceTest {

    private static final String PASSWORD = "correct-horse-battery";

    /** The production encoder's format at the cheapest cost, remembering every comparison it was asked to make. */
    private static final class RecordingEncoder implements PasswordEncoder {

        record Comparison(String raw, String hash) {
        }

        private final PasswordEncoder delegate =
                new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(4)));
        final List<Comparison> comparisons = new ArrayList<>();

        @Override
        public String encode(CharSequence raw) {
            return delegate.encode(raw);
        }

        @Override
        public boolean matches(CharSequence raw, String hash) {
            comparisons.add(new Comparison(raw.toString(), hash));
            return delegate.matches(raw, hash);
        }
    }

    private final RecordingEncoder encoder = new RecordingEncoder();
    private final UserService users = mock(UserService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final TestClock clock = new TestClock();
    private final AppProperties properties = TestAppProperties.withJwt(TestAuth.testSecret(), false, TestAuth.ISSUER, Duration.ofMinutes(15));
    private final JwtService jwt = new JwtService(JwtKeyProvider.from(properties.security().jwt()), properties, clock);
    private final JwtDecoder decoder = AccessTokenDecoder.create(
            JwtKeyProvider.from(properties.security().jwt()).signingKey(), TestAuth.ISSUER, clock);
    private final IssuedRefreshToken issued = new IssuedRefreshToken("refresh-secret", Instant.parse("2026-10-19T12:00:00Z"));

    private final AuthService auth = new AuthService(users, refreshTokens, jwt, encoder, TransactionOperations.withoutTransaction(),
            new LoginThrottle(TestAppProperties.withJwt(null, true, "tech-interviewer", Duration.ofMinutes(15)), clock));

    private User account(String password) {
        return User.candidate("ada@example.com", encoder.encode(password), "Ada", TestClock.START);
    }

    private static void assertInvalidCredentials(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(exception.getMessage()).isEqualTo(AuthService.INVALID_CREDENTIALS_DETAIL);
    }

    // ---- login -------------------------------------------------------------------------------------------

    @Test
    void anUnknownAddressStillCostsOnePasswordComparisonAgainstAValidDummyHash() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        encoder.comparisons.clear();

        assertThatThrownBy(() -> auth.login(new LoginRequest("nobody@example.com", PASSWORD)))
                .satisfies(AuthServiceTest::assertInvalidCredentials);

        assertThat(encoder.comparisons).hasSize(1);
        RecordingEncoder.Comparison comparison = encoder.comparisons.get(0);
        assertThat(comparison.raw()).isEqualTo(PASSWORD);
        assertThat(comparison.hash()).startsWith("{bcrypt}$2");
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void theDummyHashIsDrawnAtStartupAndStaysTheSame() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        encoder.comparisons.clear();

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> auth.login(new LoginRequest("nobody@example.com", PASSWORD))).isInstanceOf(ApiException.class);
        }

        assertThat(encoder.comparisons).hasSize(3);
        assertThat(encoder.comparisons).extracting(RecordingEncoder.Comparison::hash).containsOnly(encoder.comparisons.get(0).hash());
    }

    @Test
    void theDummyHashBelongsToNoAccountAndNobodyKnowsItsPassword() {
        User ada = account(PASSWORD);
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        encoder.comparisons.clear();
        assertThatThrownBy(() -> auth.login(new LoginRequest("nobody@example.com", PASSWORD))).isInstanceOf(ApiException.class);

        String dummy = encoder.comparisons.get(0).hash();

        assertThat(dummy).isNotEqualTo(ada.getPasswordHash());
        assertThat(encoder.matches(PASSWORD, dummy)).isFalse();
    }

    @Test
    void aWrongPasswordIsTheSameFailureAsAnUnknownAddress() {
        User ada = account(PASSWORD);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        encoder.comparisons.clear();

        Throwable wrong = catchThrowable(() -> auth.login(new LoginRequest("ada@example.com", "not-the-password")));
        Throwable unknown = catchThrowable(() -> auth.login(new LoginRequest("nobody@example.com", "not-the-password")));

        assertInvalidCredentials(wrong);
        assertInvalidCredentials(unknown);
        assertThat(encoder.comparisons).as("one comparison each").hasSize(2);
        assertThat(encoder.comparisons.get(0).hash()).isEqualTo(ada.getPasswordHash());
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void aDisabledAccountIsRefusedEvenWithTheRightPassword() {
        User ada = account(PASSWORD);
        ReflectionTestUtils.setField(ada, "enabled", false);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        encoder.comparisons.clear();

        assertThatThrownBy(() -> auth.login(new LoginRequest("ada@example.com", PASSWORD)))
                .satisfies(AuthServiceTest::assertInvalidCredentials);

        assertThat(encoder.comparisons).as("same work as for any other failure").hasSize(1);
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void aSuccessfulLoginStartsAFamilyAndIssuesAnAccessTokenWithTheRoleOfTheAccount() {
        User ada = account(PASSWORD);
        ReflectionTestUtils.setField(ada, "role", Role.ADMIN);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(refreshTokens.startFamily(ada.getId())).thenReturn(issued);

        AuthSession session = auth.login(new LoginRequest("ada@example.com", PASSWORD));

        assertThat(session.refreshToken()).isSameAs(issued);
        assertThat(session.body().tokenType()).isEqualTo("Bearer");
        assertThat(session.body().expiresIn()).isEqualTo(900);
        assertThat(session.body().user().id()).isEqualTo(ada.getId());
        assertThat(session.body().user().email()).isEqualTo("ada@example.com");
        assertThat(session.body().user().role()).isEqualTo(Role.ADMIN);
        Jwt token = decoder.decode(session.body().accessToken());
        assertThat(token.getSubject()).isEqualTo(ada.getId().toString());
        assertThat(token.getClaimAsString("role")).isEqualTo("ADMIN");
    }

    // ---- the per-account throttle ------------------------------------------------------------------------

    private void failTimes(String address, int times) {
        for (int i = 0; i < times; i++) {
            assertThatThrownBy(() -> auth.login(new LoginRequest(address, "wrong-password-guess"))).satisfies(AuthServiceTest::assertInvalidCredentials);
        }
    }

    @Test
    void tenFailuresBlockTheAddressEvenForTheRightPasswordUntilTheWindowPasses() {
        User ada = account(PASSWORD);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(refreshTokens.startFamily(ada.getId())).thenReturn(issued);
        failTimes("ada@example.com", 10);
        encoder.comparisons.clear();

        assertThatThrownBy(() -> auth.login(new LoginRequest("ada@example.com", PASSWORD))).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(Long.parseLong(refused.headers().get("Retry-After"))).isBetween(14L * 60, 15L * 60);
        });
        assertThat(encoder.comparisons).as("a refused attempt costs the guesser nothing to try again and us no hashing").isEmpty();

        clock.advance(Duration.ofMinutes(15));
        assertThat(auth.login(new LoginRequest("ada@example.com", PASSWORD)).refreshToken()).isSameAs(issued);
    }

    @Test
    void anAddressWithoutAnAccountIsThrottledTheSameWayAndTheCaseOfTheAddressDoesNotMatter() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());

        failTimes("Nobody@Example.com", 5);
        failTimes("nobody@example.com", 5);

        assertThatThrownBy(() -> auth.login(new LoginRequest("NOBODY@example.com", PASSWORD))).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).as("the answer does not tell whether the address is registered")
                        .isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void aSuccessfulSignInClearsTheCountAndOtherAddressesAreNotAffected() {
        User ada = account(PASSWORD);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(users.findByEmail("grace@example.com")).thenReturn(Optional.empty());
        when(refreshTokens.startFamily(ada.getId())).thenReturn(issued);

        failTimes("ada@example.com", 9);
        failTimes("grace@example.com", 9);
        auth.login(new LoginRequest("ada@example.com", PASSWORD));
        failTimes("ada@example.com", 9);

        assertThat(auth.login(new LoginRequest("ada@example.com", PASSWORD)).refreshToken()).isSameAs(issued);
    }

    // ---- register ----------------------------------------------------------------------------------------

    @Test
    void registeringStoresAHashNotThePasswordAndSignsTheNewAccountIn() {
        when(users.registerCandidate(eq("ada@example.com"), anyString(), eq("Ada"))).thenAnswer(call ->
                User.candidate(call.getArgument(0), call.getArgument(1), call.getArgument(2), TestClock.START));
        when(refreshTokens.startFamily(any(UUID.class))).thenReturn(issued);

        AuthSession session = auth.register(new RegisterRequest("Ada@Example.com", PASSWORD, "Ada"));

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).registerCandidate(eq("ada@example.com"), hash.capture(), eq("Ada"));
        assertThat(hash.getValue()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash.getValue())).isTrue();
        assertThat(session.body().user().role()).isEqualTo(Role.CANDIDATE);
        assertThat(session.body().user().email()).isEqualTo("ada@example.com");
        assertThat(session.refreshToken()).isSameAs(issued);
        assertThat(decoder.decode(session.body().accessToken()).getSubject()).isEqualTo(session.body().user().id().toString());
        verify(refreshTokens).startFamily(session.body().user().id());
    }

    @Test
    void aRegistrationThatLosesTheEmailIsNotSignedIn() {
        ApiException taken = new ApiException(ErrorCode.EMAIL_TAKEN, "taken");
        when(users.registerCandidate(anyString(), anyString(), anyString())).thenThrow(taken);

        assertThatThrownBy(() -> auth.register(new RegisterRequest("ada@example.com", PASSWORD, "Ada"))).isSameAs(taken);

        verifyNoInteractions(refreshTokens);
    }

    // ---- refresh and logout ------------------------------------------------------------------------------

    @Test
    void refreshWithoutATokenIsUnauthenticatedAndTouchesNothing() {
        assertThatThrownBy(() -> auth.refresh(null)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
            assertThat(ex.getMessage()).isEqualTo(AuthService.REFRESH_REJECTED_DETAIL);
        });
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void aRefusedRefreshTokenIsUnauthenticated() {
        when(refreshTokens.rotate("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.refresh("bad")).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void anAcceptedRefreshTokenYieldsAFreshAccessTokenForTheAccountAndTheReplacementToken() {
        User ada = account(PASSWORD);
        when(refreshTokens.rotate("good")).thenReturn(Optional.of(new RefreshTokenService.Rotation(ada, issued)));

        AuthSession session = auth.refresh("good");

        assertThat(session.refreshToken()).isSameAs(issued);
        assertThat(decoder.decode(session.body().accessToken()).getSubject()).isEqualTo(ada.getId().toString());
        assertThat(session.body().user().id()).isEqualTo(ada.getId());
    }

    @Test
    void logoutRevokesTheFamilyOfThePresentedTokenAndIgnoresAMissingOne() {
        auth.logout(null);
        verifyNoInteractions(refreshTokens);

        auth.logout("some-token");
        verify(refreshTokens).revokeFamilyOf("some-token");
    }
}
