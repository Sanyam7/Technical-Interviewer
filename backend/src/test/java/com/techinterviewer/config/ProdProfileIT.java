package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The production profile, started for real: what it switches off, what it requires and how it logs. */
@SpringBootTest(properties = {
    "APP_CORS_ALLOWED_ORIGINS=https://app.example.com",
    // prod turns the AI layer on, which needs a provider; no test here calls it.
    "GEMINI_API_KEY=placeholder-key-never-sent",
    // the throw-away test database is owned by a superuser, which the production profile would refuse (RuntimeRoleIT checks that)
    "app.database.role-check=false",
    "logging.level.org.springframework.security.web.FilterChainProxy=DEBUG"
})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ProdProfileIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void apiDocumentationAndSwaggerUiAreNotServed() throws Exception {
        for (String path : List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void healthAndProbesWork() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void onlyTheConfiguredOriginIsAllowedByCors() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"));
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void forwardedHeadersAreResolvedByTomcatNotBySpring() throws Exception {
        // server.forward-headers-strategy=native: Tomcat's RemoteIpValve (not exercised by MockMvc) rewrites the client
        // address; Spring's ForwardedHeaderFilter, which would rewrite paths from X-Forwarded-Prefix, is not installed.
        // ClientIpIT proves the address handling against a real server.
        mockMvc.perform(get("/api/v1/interviews").header("X-Forwarded-Prefix", "/pre"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.instance").value("/api/v1/interviews"));
    }

    @Test
    void logsAreEcsJsonAndCarryTheTraceIdOfTheRequest(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Request-Id", "prod-trace-0001")).andExpect(status().isOk());

        List<JsonNode> requestLogs = output.getAll().lines()
                .filter(line -> line.startsWith("{"))
                .map(jsonMapper::readTree)
                .filter(entry -> entry.path("message").asString("").contains("Securing GET /api/v1/health"))
                .toList();

        assertThat(requestLogs).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.path("@timestamp").asString("")).isNotBlank();
            assertThat(entry.path("log").path("level").asString("")).isEqualTo("DEBUG");
            assertThat(entry.path("ecs").path("version").asString("")).isNotBlank();
            assertThat(entry.path("traceId").asString("")).isEqualTo("prod-trace-0001");
        });
    }
}
