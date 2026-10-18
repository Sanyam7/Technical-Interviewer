package com.techinterviewer.security;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.user.EmailAddress;
import com.techinterviewer.security.dto.LoginRequest;
import com.techinterviewer.security.dto.RegisterRequest;
import com.techinterviewer.security.dto.TokenResponse;
import com.techinterviewer.user.User;
import com.techinterviewer.user.UserMapper;
import com.techinterviewer.user.UserService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Registration, login, token refresh and logout. Each operation performs at most one short database transaction;
 * the expensive password hashing happens outside of it.
 */
@Service
class AuthService {

    static final String INVALID_CREDENTIALS_DETAIL = "The email address or the password is not correct.";
    static final String TOO_MANY_FAILURES_DETAIL = "Too many failed sign-in attempts for this address. Wait before trying again.";
    static final String REFRESH_REJECTED_DETAIL = "The refresh token is missing, expired or no longer valid. Sign in again.";

    private final UserService users;
    private final RefreshTokenService refreshTokens;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;
    private final TransactionOperations transaction;
    private final LoginThrottle throttle;

    /**
     * A well-formed hash of a random password nobody knows. Comparing against it when the account does not
     * exist makes that login do the same BCrypt work as one for an existing account, so the response time
     * does not reveal which addresses are registered.
     */
    private final String unknownAccountHash;

    AuthService(
            UserService users,
            RefreshTokenService refreshTokens,
            JwtService jwt,
            PasswordEncoder passwordEncoder,
            TransactionOperations transaction,
            LoginThrottle throttle) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.passwordEncoder = passwordEncoder;
        this.transaction = transaction;
        this.throttle = throttle;
        this.unknownAccountHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Creates a candidate account and signs it in. The account and its first refresh token commit together. */
    AuthSession register(RegisterRequest request) {
        PasswordPolicy.weakness(request.password(), request.email(), request.displayName())
                .ifPresent(reason -> {
                    throw new FieldValidationException("password", reason);
                });
        String passwordHash = passwordEncoder.encode(request.password());
        return inTransaction(() -> {
            User user = users.registerCandidate(request.email(), passwordHash, request.displayName());
            return sessionFor(user, refreshTokens.startFamily(user.getId()));
        });
    }

    /**
     * Signs in. Every failure (unknown address, wrong password, disabled account) is the same 401 and costs the
     * same BCrypt comparison.
     */
    AuthSession login(LoginRequest request) {
        Optional<java.time.Duration> blocked = throttle.blockedFor(request.email());
        if (blocked.isPresent()) {
            throw new ApiException(ErrorCode.RATE_LIMITED, TOO_MANY_FAILURES_DETAIL).withRetryAfter(blocked.get());
        }
        Optional<User> found = EmailAddress.isWellFormed(request.email())
                ? users.findByEmail(request.email())
                : Optional.empty();
        String storedHash = found.map(User::getPasswordHash).orElse(unknownAccountHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);
        if (found.isEmpty() || !passwordMatches || !found.get().isEnabled()) {
            throttle.failed(request.email());
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL);
        }
        User user = found.get();
        throttle.succeeded(request.email());
        return inTransaction(() -> sessionFor(user, refreshTokens.startFamily(user.getId())));
    }

    /**
     * Exchanges a refresh token for a new access token and a replacement refresh token.
     *
     * @throws ApiException {@code UNAUTHENTICATED} when there is no token or it is not acceptable
     */
    AuthSession refresh(@Nullable String presentedToken) {
        RefreshTokenService.Rotation rotation = Optional.ofNullable(presentedToken)
                .flatMap(refreshTokens::rotate)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, REFRESH_REJECTED_DETAIL));
        return sessionFor(rotation.user(), rotation.token());
    }

    /** Ends the session of the presented token. Without a token, or with one that is unknown, nothing happens. */
    void logout(@Nullable String presentedToken) {
        if (presentedToken != null) {
            refreshTokens.revokeFamilyOf(presentedToken);
        }
    }

    private AuthSession inTransaction(Supplier<AuthSession> work) {
        return Objects.requireNonNull(transaction.execute(status -> work.get()));
    }

    private AuthSession sessionFor(User user, IssuedRefreshToken refreshToken) {
        IssuedToken accessToken = jwt.issueAccessToken(new AuthenticatedUser(user.getId(), user.getRole()));
        TokenResponse body = new TokenResponse(
                accessToken.value(), TokenResponse.BEARER, accessToken.expiresInSeconds(), UserMapper.toResponse(user));
        return new AuthSession(body, refreshToken);
    }
}
