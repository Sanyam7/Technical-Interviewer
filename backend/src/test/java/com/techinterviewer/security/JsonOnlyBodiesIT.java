package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.user.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Request bodies are parsed by the JSON mapper that refuses unknown properties, and by nothing else. The classpath
 * also holds YAML and XML converters (a transitive dependency of the OpenAPI library) whose mappers ignore unknown
 * properties, so every endpoint that reads a body must say it consumes JSON only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JsonOnlyBodiesIT {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired
    MockMvc mockMvc;

    @Test
    void everyEndpointThatReadsABodyConsumesApplicationJsonOnly() {
        List<String> offenders = new ArrayList<>();
        int withBody = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod method = entry.getValue();
            boolean readsBody = false;
            for (MethodParameter parameter : method.getMethodParameters()) {
                readsBody |= parameter.hasParameterAnnotation(RequestBody.class);
            }
            if (!readsBody) {
                continue;
            }
            withBody++;
            Set<MediaType> consumes = info.getConsumesCondition().getConsumableMediaTypes();
            if (!consumes.equals(Set.of(MediaType.APPLICATION_JSON))) {
                offenders.add(info + " consumes " + consumes);
            }
        }

        assertThat(withBody).as("endpoints that read a body").isGreaterThanOrEqualTo(7);
        assertThat(offenders).isEmpty();
    }

    @Test
    void yamlIsRefusedWhereItUsedToSlipPastTheUnknownPropertyCheck() throws Exception {
        String yaml = "email: ada@example.com\npassword: correct-horse-battery\nrole: ADMIN\n";
        MediaType yamlType = MediaType.parseMediaType("application/yaml");

        mockMvc.perform(post("/api/v1/auth/register").contentType(yamlType).content(yaml))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post("/api/v1/auth/login").contentType(yamlType).content(yaml))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post("/api/v1/interviews/" + UUID.randomUUID() + "/questions/" + UUID.randomUUID() + "/skip")
                        .with(TestAuth.bearer(UUID.randomUUID(), Role.CANDIDATE))
                        .contentType(yamlType)
                        .content("timeTakenSeconds: 5\nscore: 100\n"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(put("/api/v1/profile")
                        .with(TestAuth.bearer(UUID.randomUUID(), Role.CANDIDATE))
                        .contentType(yamlType)
                        .content("skills: []\n"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
