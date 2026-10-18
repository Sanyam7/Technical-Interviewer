package com.techinterviewer.security;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.user.Role;
import java.util.UUID;

/**
 * The account behind a request, as proven by its access token. Controllers receive it through a
 * {@link CurrentUser} parameter and pass {@code id()} to services, which scope every query by it.
 */
public record AuthenticatedUser(UUID id, Role role) {

    public AuthenticatedUser {
        Require.nonNull(id, "id");
        Require.nonNull(role, "role");
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
