package com.techinterviewer.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthControllerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BuildProperties build;

    @Autowired
    WebEndpointsSupplier webEndpoints;

    @Test
    void healthIsPublicAndFollowsTheContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("application/json")))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.service").value("tech-interviewer"))
                .andReturn();

        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("status", "service", "version", "serverTime", "database");
        assertThat(body.get("version").asString()).isNotBlank().isEqualTo(build.getVersion());
        assertThat(Instant.parse(body.get("serverTime").asString())).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void healthEchoesAValidRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Request-Id", "client-supplied-id-42"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "client-supplied-id-42"));
    }

    @Test
    void healthGeneratesARequestIdWhenNoneIsSent() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void flywayInstalledTheRequiredExtensions() {
        List<String> extensions = jdbc.sql("SELECT extname FROM pg_extension").query(String.class).list();

        assertThat(extensions).contains("vector", "citext");
    }

    @Test
    void flywayAppliedV1() {
        String description = jdbc.sql("SELECT description FROM flyway_schema_history WHERE version = '1' AND success")
                .query(String.class)
                .single();

        assertThat(description).isEqualTo("extensions");
    }

    @Test
    void actuatorHealthIsUpAndExposesNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void onlyHealthAndInfoActuatorEndpointsAreExposed() {
        List<String> exposed = webEndpoints.getEndpoints().stream()
                .map(ExposableWebEndpoint::getEndpointId)
                .map(Object::toString)
                .toList();

        assertThat(exposed).containsExactlyInAnyOrder("health", "info");
    }

    @Test
    void sensitiveActuatorEndpointsAreNotReachable() throws Exception {
        for (String endpoint : List.of("env", "beans", "metrics", "heapdump", "configprops", "mappings")) {
            mockMvc.perform(get("/actuator/" + endpoint)).andExpect(status().isUnauthorized());
        }
    }
}
