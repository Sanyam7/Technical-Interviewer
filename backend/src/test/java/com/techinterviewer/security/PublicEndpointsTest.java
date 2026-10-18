package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PublicEndpointsTest {

    @ParameterizedTest(name = "{0} {1} public={2}")
    @CsvSource({
        "GET,  /api/v1/health,                       true",
        "HEAD, /api/v1/health,                       true",
        "POST, /api/v1/health,                       false",
        "GET,  /actuator/health,                     true",
        "GET,  /actuator/health/liveness,            true",
        "GET,  /actuator/info,                       true",
        "GET,  /actuator/infoX,                      false",
        "GET,  /actuator/env,                        false",
        "GET,  /v3/api-docs,                         true",
        "GET,  /v3/api-docs/swagger-config,          true",
        "GET,  /swagger-ui/index.html,               true",
        "POST, /api/v1/auth/register,                true",
        "POST, /api/v1/auth/login,                   true",
        "POST, /api/v1/auth/refresh,                 true",
        "POST, /api/v1/auth/logout,                  true",
        "GET,  /api/v1/auth/login,                   false",
        "POST, /api/v1/auth/login/x,                 false",
        "POST, /api/v1/auth/me,                      false",
        "GET,  /api/v1/catalog/skills,               true",
        "GET,  /api/v1/catalog/skills/{slug}/topics, true",
        "GET,  /api/v1/catalog/options,              true",
        "POST, /api/v1/catalog/skills,               false",
        "DELETE, /api/v1/catalog/skills,             false",
        "GET,  /api/v1/interviews,                   false",
        "POST, /api/v1/interviews,                   false",
        "GET,  /api/v1/users/me,                     false",
        "GET,  /api/v1/admin/anything,               false"
    })
    void classifiesEveryOperation(String method, String path, boolean isPublic) {
        assertThat(PublicEndpoints.isPublic(method, path)).isEqualTo(isPublic);
    }
}
