package com.techinterviewer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.catalog.dto.OptionsResponse;
import com.techinterviewer.catalog.dto.SkillResponse;
import com.techinterviewer.catalog.dto.TopicResponse;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.security.WithApplicationSecurity;
import com.techinterviewer.user.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP behaviour of the public catalog: no token needed, cacheable, validatable, errors as problems. */
@WebMvcTest(CatalogController.class)
@WithApplicationSecurity
@Import(CatalogWebConfig.class)
class CatalogControllerTest {

    private static final String BASE = "/api/v1/catalog";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CatalogService service;

    private static List<SkillResponse> skills(String slug) {
        return List.of(new SkillResponse(1, slug, "Name of " + slug, SkillCategory.LANGUAGE, "About " + slug));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/options", "/skills", "/skills/java/topics"})
    void everyCatalogEndpointIsPublicAndCacheableForOneHour(String path) throws Exception {
        stub();

        mockMvc.perform(get(BASE + path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "public, max-age=3600"))
                .andExpect(header().exists("ETag"))
                .andExpect(header().doesNotExist("Pragma"))
                .andExpect(header().doesNotExist("Expires"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/options", "/skills", "/skills/java/topics"})
    void aTokenSentToACatalogEndpointIsIgnoredEvenWhenItIsExpiredOrForged(String path) throws Exception {
        stub();
        TestAuth auth = TestAuth.standalone();
        UUID user = UUID.randomUUID();

        mockMvc.perform(get(BASE + path).header("Authorization", "Bearer " + auth.expiredToken(user, Role.CANDIDATE)))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + path).header("Authorization", "Bearer " + auth.algNoneToken(user, Role.CANDIDATE)))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + path).with(TestAuth.bearer(user, Role.CANDIDATE))).andExpect(status().isOk());
    }

    @Test
    void anUnchangedDocumentIsRevalidatedWith304AndNoBody() throws Exception {
        stub();
        MvcResult first = mockMvc.perform(get(BASE + "/skills")).andExpect(status().isOk()).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();

        mockMvc.perform(get(BASE + "/skills").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(header().string("Cache-Control", "public, max-age=3600"))
                .andExpect(content().string(""));
    }

    @Test
    void aChangedDocumentHasAnotherEtagAndIsNotAnsweredWith304() throws Exception {
        when(service.activeSkills()).thenReturn(skills("java"));
        String oldEtag = mockMvc.perform(get(BASE + "/skills")).andReturn().getResponse().getHeader("ETag");

        when(service.activeSkills()).thenReturn(skills("python"));
        MvcResult changed = mockMvc.perform(get(BASE + "/skills").header("If-None-Match", oldEtag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("python"))
                .andReturn();

        assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(oldEtag);
    }

    @Test
    void anUnknownSkillIsAProblemThatIsNeitherCachedNorTagged() throws Exception {
        when(service.topicsOfSkill("cobol")).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "Skill not found."));

        mockMvc.perform(get(BASE + "/skills/cobol/topics"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Skill not found."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", not(containsString("public"))));
    }

    @Test
    void headWorksWhereGetDoes() throws Exception {
        stub();

        mockMvc.perform(head(BASE + "/skills"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=3600"));
    }

    @Test
    void theCatalogIsReadOnlyForEveryoneIncludingTheAuthenticated() throws Exception {
        mockMvc.perform(post(BASE + "/skills")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/skills").with(TestAuth.bearer(UUID.randomUUID(), Role.ADMIN))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void theBodiesHaveTheDocumentedShapeAndOmitInternalFields() throws Exception {
        stub();

        mockMvc.perform(get(BASE + "/skills"))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].slug").value("java"))
                .andExpect(jsonPath("$[0].name").value("Name of java"))
                .andExpect(jsonPath("$[0].category").value("LANGUAGE"))
                .andExpect(jsonPath("$[0].description").value("About java"))
                .andExpect(jsonPath("$[0].active").doesNotExist());
        mockMvc.perform(get(BASE + "/skills/java/topics"))
                .andExpect(jsonPath("$[0].slug").value("generics"))
                .andExpect(jsonPath("$[0].name").value("Generics"))
                .andExpect(jsonPath("$[0].description").value("Type parameters"))
                .andExpect(jsonPath("$[0].minDifficulty").value(2))
                .andExpect(jsonPath("$[0].maxDifficulty").value(4))
                .andExpect(jsonPath("$[0].weight").doesNotExist())
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].skillId").doesNotExist());
    }

    private void stub() {
        when(service.options()).thenReturn(new OptionsResponse(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(service.activeSkills()).thenReturn(skills("java"));
        when(service.topicsOfSkill("java")).thenReturn(List.of(new TopicResponse("generics", "Generics", "Type parameters", 2, 4)));
    }
}
