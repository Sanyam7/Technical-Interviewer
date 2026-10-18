package com.techinterviewer.security;

import com.techinterviewer.security.dto.TokenResponse;

/** The outcome of an authentication: the JSON body and the refresh token that goes into the cookie. */
record AuthSession(TokenResponse body, IssuedRefreshToken refreshToken) {
}
