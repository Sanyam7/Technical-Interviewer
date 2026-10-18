package com.techinterviewer.security;

import com.techinterviewer.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The cookie that carries the refresh token: {@code HttpOnly} (scripts cannot read it), {@code SameSite=Lax} by default
 * ({@code None} when the app and the API are on different sites, see app.security.refresh-cookie.same-site),
 * limited to the auth endpoints by its path, {@code Secure} unless configured otherwise (plain-http local
 * development) and living exactly as long as the token.
 */
@Component
class RefreshCookieFactory {

    private final String name;
    private final boolean secure;
    private final Duration ttl;
    private final String path;
    private final String sameSite;

    RefreshCookieFactory(AppProperties properties) {
        AppProperties.RefreshCookie cookie = properties.security().refreshCookie();
        this.name = cookie.name();
        this.secure = cookie.secure();
        this.ttl = cookie.ttl();
        this.path = cookie.path();
        this.sameSite = cookie.sameSite();
    }

    ResponseCookie issue(IssuedRefreshToken token) {
        return base(token.value()).maxAge(ttl).build();
    }

    /** An immediately expiring cookie with the same name and path, which makes the browser delete the real one. */
    ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    /** The refresh token the request carries, if any. */
    @Nullable String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite(sameSite).path(path);
    }
}
