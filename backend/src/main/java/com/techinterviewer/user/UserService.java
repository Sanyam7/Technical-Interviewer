package com.techinterviewer.user;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.user.dto.UserResponse;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    /** The unique constraint of {@code users.email} (see {@code V2__identity.sql}). */
    static final String EMAIL_CONSTRAINT = "uq_users_email";

    static final String EMAIL_TAKEN_DETAIL = "An account with this email address already exists.";

    private final UserRepository users;
    private final Clock clock;

    UserService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /**
     * Creates a candidate account. Joins the caller's transaction, so the account and whatever the caller stores
     * with it commit or roll back together.
     *
     * @param email        canonical email, see {@link EmailAddress#normalize}
     * @param passwordHash the encoded password
     * @throws ApiException {@code EMAIL_TAKEN} when the address is already registered, also when a concurrent
     *                      registration of the same address wins the race for the unique constraint
     */
    @Transactional
    public User registerCandidate(String email, String passwordHash, String displayName) {
        if (users.existsByEmail(email)) {
            throw emailTaken(null);
        }
        try {
            return users.saveAndFlush(User.candidate(email, passwordHash, displayName, clock.instant()));
        } catch (DataIntegrityViolationException ex) {
            if (violatesEmailConstraint(ex)) {
                throw emailTaken(ex);
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String canonicalEmail) {
        return users.findByEmail(canonicalEmail);
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        return users.findById(id);
    }

    /**
     * The current view of an enabled account.
     *
     * <p>Only this lookup, login and refresh re-check the account. Every other endpoint trusts the signed token
     * until it expires (15 minutes), so disabling an account or changing its role takes effect on the next refresh
     * at the latest, not instantly. That is the accepted cost of stateless access tokens.
     *
     * @throws ApiException {@code UNAUTHENTICATED} when the account no longer exists or was disabled
     */
    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID id) {
        return users.findById(id)
                .filter(User::isEnabled)
                .map(UserMapper::toResponse)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "The account of this token is not active."));
    }

    private static boolean violatesEmailConstraint(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && EMAIL_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static ApiException emailTaken(@Nullable Throwable cause) {
        return new ApiException(ErrorCode.EMAIL_TAKEN, EMAIL_TAKEN_DETAIL, cause);
    }
}
