package com.techinterviewer.security;

import static com.techinterviewer.security.ClientIpProbe.clientIp;
import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Everywhere except production there is no proxy: the forwarded header is just a header and the peer address is the client. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(SecurityTestController.PROFILE)
@Import(TestcontainersConfiguration.class)
class ClientIpDefaultIT {

    @LocalServerPort
    int port;

    @Test
    void aForwardedHeaderIsNotBelievedWithoutTheProdProfile() throws Exception {
        assertThat(clientIp(port, "203.0.113.9")).isIn("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    }
}
