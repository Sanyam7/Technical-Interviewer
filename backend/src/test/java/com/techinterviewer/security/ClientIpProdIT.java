package com.techinterviewer.security;

import static com.techinterviewer.security.ClientIpProbe.clientIp;
import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The per-IP rate limits read {@code request.getRemoteAddr()}. Behind a proxy that must be the client the proxy saw,
 * and a client must not be able to choose it. Needs a real server: MockMvc does not run Tomcat's remote IP valve.
 * Here the production profile is active and this test plays the proxy (on loopback, which Tomcat trusts by default).
 */
// prod turns the AI layer on, which needs a provider; no test here calls it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"APP_CORS_ALLOWED_ORIGINS=https://app.example.com", "GEMINI_API_KEY=placeholder-key-never-sent",
            // the throw-away test database is owned by a superuser, which the production profile refuses (RuntimeRoleIT checks that)
            "app.database.role-check=false"})
@ActiveProfiles({"prod", SecurityTestController.PROFILE})
@Import(TestcontainersConfiguration.class)
class ClientIpProdIT {

    @LocalServerPort
    int port;

    @Test
    void theClientAddressIsTheOneTheTrustedProxyForwarded() throws Exception {
        assertThat(clientIp(port, "203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void addressesAppendedByTheClientItselfAreIgnoredOnlyTheLastUntrustedHopCounts() throws Exception {
        // "spoofed, real": the proxy appends the address it saw to whatever the client sent.
        assertThat(clientIp(port, "1.2.3.4, 203.0.113.9")).isEqualTo("203.0.113.9");
        // Internal hops of the proxy chain are skipped.
        assertThat(clientIp(port, "203.0.113.9, 10.0.0.5")).isEqualTo("203.0.113.9");
    }

    @Test
    void withoutTheHeaderTheAddressIsTheDirectPeer() throws Exception {
        assertThat(clientIp(port, null)).isIn("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    }
}
