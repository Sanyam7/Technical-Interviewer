package com.techinterviewer.security.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestJson;
import com.techinterviewer.user.Role;
import com.techinterviewer.user.dto.UserResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TokenResponseTest {

    private final TokenResponse response = new TokenResponse(
            "eyJ.access.token", TokenResponse.BEARER, 900,
            new UserResponse(UUID.fromString("0192f5c2-8a3e-7c1d-9b1e-3f4a5b6c7d8e"), "ada@example.com", "Ada", Role.CANDIDATE));

    @Test
    void jsonHasExactlyTheDocumentedMembers() throws Exception {
        JsonNode json = TestJson.applicationMapper().readTree(TestJson.applicationMapper().writeValueAsString(response));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn", "user");
        assertThat(json.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(json.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(json.get("user").propertyNames()).containsExactlyInAnyOrder("id", "email", "displayName", "role");
    }

    @Test
    void toStringNeverContainsTheAccessToken() {
        assertThat(response.toString()).doesNotContain("eyJ.access.token").contains("ada@example.com");
    }
}
