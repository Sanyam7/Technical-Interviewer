package com.techinterviewer.security;

import com.techinterviewer.user.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that prove the authorisation rules. The profile keeps component scanning in Spring Boot
 * tests from ever registering it; a test activates it with {@code @ActiveProfiles(SecurityTestController.PROFILE)}.
 */
@RestController
@Profile(SecurityTestController.PROFILE)
@RequestMapping("/api/v1")
public class SecurityTestController {

    public static final String PROFILE = "security-test";

    public record Me(UUID id, Role role) {
    }

    public record Ok(boolean ok) {
    }

    /** Any authenticated account. */
    @GetMapping("/test/secured")
    Ok secured() {
        return new Ok(true);
    }

    /** Changes nothing; exists to give the documentation a secured POST. */
    @PostMapping("/test/echo")
    Ok echo() {
        return new Ok(true);
    }

    /** {@code /api/v1/admin/**} needs ROLE_ADMIN. */
    @GetMapping("/admin/test")
    Ok admin() {
        return new Ok(true);
    }

    /** The account comes from the verified token, never from the request. */
    @GetMapping("/test/me")
    Me me(@CurrentUser AuthenticatedUser user) {
        return new Me(user.id(), user.role());
    }

    /** What the application believes the client address is (used to prove the proxy configuration). */
    @GetMapping("/test/client-ip")
    String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
