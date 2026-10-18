package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.user.Role;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Asks the running server which client address it believes the request came from. */
final class ClientIpProbe {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private ClientIpProbe() {
    }

    static String clientIp(int port, @Nullable String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/test/client-ip"))
                .header("Authorization", "Bearer " + TestAuth.standalone().token(UUID.randomUUID(), Role.CANDIDATE));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
