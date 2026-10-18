package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.security.SecurityTestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", SecurityTestController.PROFILE})
@Import(TestcontainersConfiguration.class)
class OpenApiLocalIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    BuildProperties build;

    @Test
    void apiDocsListTheHealthEndpointAndItsSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("AI Technical Interviewer API"))
                .andExpect(jsonPath("$.paths['/api/v1/health'].get.operationId").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['503']").exists())
                .andExpect(jsonPath("$.components.schemas.HealthResponse.properties.database").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    void apiDocsCarryTheBuildVersion() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"version\":\"" + build.getVersion() + "\"");
    }

    @Test
    void swaggerUiIsReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void bearerAuthIsTheDefaultSecurityRequirementAndPublicOperationsOptOut() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                // health is public: its operation has an explicit empty security list and no 401/403
                .andExpect(jsonPath("$.paths['/api/v1/health'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['401']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['403']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/health'].get.responses['429']").doesNotExist());
    }

    @Test
    void securedOperationsDocumentTheProblemResponses() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/test/secured'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/test/secured'].get.responses['401'].content['application/problem+json'].schema['$ref']")
                        .value("#/components/schemas/Problem"))
                .andExpect(jsonPath("$.paths['/api/v1/test/secured'].get.responses['403'].content['application/problem+json']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/test/secured'].get.responses['429']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/test/echo'].post.responses['429'].content['application/problem+json']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/test/echo'].post.responses['401']").exists());
    }

    @Test
    void theProblemSchemaListsEveryErrorCode() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.Problem.properties.code.enum").value(org.hamcrest.Matchers.hasItems(
                        "UNAUTHENTICATED", "TOKEN_EXPIRED", "RATE_LIMITED", "CONCURRENT_MODIFICATION", "AI_PROVIDER_UNAVAILABLE")))
                .andExpect(jsonPath("$.components.schemas.Problem.required").value(org.hamcrest.Matchers.hasItems("type", "title", "status", "code")))
                .andExpect(jsonPath("$.components.schemas.Problem.properties.errors.items.properties.field").exists());
    }

    @Test
    void theCurrentUserParameterIsNotAnInputOfTheApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/test/me'].get.parameters").doesNotExist());
    }
}
