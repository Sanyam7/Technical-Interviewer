package com.techinterviewer.catalog;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The generated API documentation lists the catalog and profile endpoints, public ones without the bearer requirement. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CatalogProfileOpenApiIT {

    @Autowired
    MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/catalog/options", "/api/v1/catalog/skills", "/api/v1/catalog/skills/{slug}/topics"})
    void catalogOperationsArePublicWithoutAnyAuthenticationResponses(String path) throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + path + "'].get.operationId").isNotEmpty())
                .andExpect(jsonPath("$.paths['" + path + "'].get.tags[0]").value("Catalog"))
                .andExpect(jsonPath("$.paths['" + path + "'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['" + path + "'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['" + path + "'].get.responses['401']").doesNotExist())
                .andExpect(jsonPath("$.paths['" + path + "'].get.responses['403']").doesNotExist());
    }

    @Test
    void catalogOperationsDescribeTheirBodiesAndTheNotFoundProblem() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/catalog/options'].get.responses['200'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/OptionsResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/catalog/skills'].get.responses['200'].content['application/json'].schema.items['$ref']")
                        .value("#/components/schemas/SkillResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/catalog/skills/{slug}/topics'].get.responses['200'].content['application/json'].schema.items['$ref']")
                        .value("#/components/schemas/TopicResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/catalog/skills/{slug}/topics'].get.responses['404'].content['application/problem+json'].schema['$ref']")
                        .value("#/components/schemas/Problem"))
                .andExpect(jsonPath("$.paths['/api/v1/catalog/skills/{slug}/topics'].get.parameters[0].name").value("slug"))
                .andExpect(jsonPath("$.paths['/api/v1/catalog/skills/{slug}/topics'].get.parameters[0].in").value("path"))
                .andExpect(jsonPath("$.components.schemas.OptionsResponse.properties.interviewTypes").exists())
                .andExpect(jsonPath("$.components.schemas.OptionsResponse.properties.durationsMinutes").exists())
                .andExpect(jsonPath("$.components.schemas.TopicResponse.properties.minDifficulty").exists())
                .andExpect(jsonPath("$.components.schemas.TopicResponse.properties.weight").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "put"})
    void profileOperationsRequireTheBearerTokenAndDocumentTheAuthenticationProblems(String method) throws Exception {
        String operation = "$.paths['/api/v1/profile']." + method;
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".tags[0]").value("Profile"))
                .andExpect(jsonPath(operation + ".security").doesNotExist())
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                .andExpect(jsonPath(operation + ".responses['200'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/ProfileResponse"))
                .andExpect(jsonPath(operation + ".responses['401'].content['application/problem+json']").exists())
                .andExpect(jsonPath(operation + ".responses['403'].content['application/problem+json']").exists());
    }

    @Test
    void theProfileUpdateDocumentsItsBodyItsLimitsAndTheValidationProblem() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/profile'].put.requestBody.required").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/profile'].put.requestBody.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/UpdateProfileRequest"))
                .andExpect(jsonPath("$.paths['/api/v1/profile'].put.responses['400'].content['application/problem+json']").exists())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.required").value(hasItems("skills")))
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.headline.maxLength").value(120))
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.jobTitle.maxLength").value(60))
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.yearsExperience.minimum").value(0))
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.yearsExperience.maximum").value(60))
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.skills.maxItems").value(20))
                .andExpect(jsonPath("$.components.schemas.ProfileSkillRequest.properties.selfRating.minimum").value(1))
                .andExpect(jsonPath("$.components.schemas.ProfileSkillRequest.properties.selfRating.maximum").value(5))
                .andExpect(jsonPath("$.components.schemas.ProfileSkillRequest.required").value(hasItems("skill", "selfRating")))
                .andExpect(jsonPath("$.components.schemas.ProfileSkillResponse.properties.name").exists());
    }

    @Test
    void theAuthenticatedUserIsNotAParameterOfTheProfileOperationsAndNoResumeFieldIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/profile'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/profile'].put.parameters").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ProfileResponse.properties.resumeDocumentId").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.resumeDocumentId").doesNotExist());
    }
}
