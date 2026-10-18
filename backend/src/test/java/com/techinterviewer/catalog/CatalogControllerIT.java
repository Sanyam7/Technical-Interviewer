package com.techinterviewer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.techinterviewer.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The public catalog through the whole application: real filter chain, real database, seeded v1 catalog. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogControllerIT {

    private static final String BASE = "/api/v1/catalog";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    CatalogTestData data;

    @BeforeEach
    void setUp() {
        data = new CatalogTestData(jdbc);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void optionsAreServedWithoutATokenInTheDocumentedShape() throws Exception {
        mockMvc.perform(get(BASE + "/options"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.roles").value(contains("BACKEND", "FRONTEND", "FULL_STACK")))
                .andExpect(jsonPath("$.experienceLevels").value(contains("INTERN", "JUNIOR", "MID", "SENIOR", "STAFF")))
                .andExpect(jsonPath("$.difficulties").value(contains("EASY", "MEDIUM", "HARD")))
                .andExpect(jsonPath("$.durationsMinutes").value(contains(15, 30, 45, 60, 90)))
                .andExpect(jsonPath("$.codingLanguages").value(contains("java", "javascript", "typescript", "python", "go")))
                .andExpect(jsonPath("$.executableLanguages").isEmpty())
                .andExpect(jsonPath("$.interviewTypes", hasSize(4)))
                .andExpect(jsonPath("$.interviewTypes[0].value").value("TECHNICAL"))
                .andExpect(jsonPath("$.interviewTypes[0].sections").value(contains("INTRODUCTION", "QUESTIONING")))
                .andExpect(jsonPath("$.interviewTypes[1].value").value("CODING"))
                .andExpect(jsonPath("$.interviewTypes[1].sections").value(contains("INTRODUCTION", "CODING")))
                .andExpect(jsonPath("$.interviewTypes[2].value").value("SYSTEM_DESIGN"))
                .andExpect(jsonPath("$.interviewTypes[2].sections").value(contains("INTRODUCTION", "SYSTEM_DESIGN")))
                .andExpect(jsonPath("$.interviewTypes[3].value").value("FULL_LOOP"))
                .andExpect(jsonPath("$.interviewTypes[3].sections")
                        .value(contains("INTRODUCTION", "QUESTIONING", "CODING", "SYSTEM_DESIGN")));
    }

    @Test
    void theSeededCatalogReturnsTheNineSkills() throws Exception {
        mockMvc.perform(get(BASE + "/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(9)))
                .andExpect(jsonPath("$[*].slug").value(org.hamcrest.Matchers.containsInAnyOrder("java", "javascript", "microservices",
                        "postgresql", "react", "rest-api", "spring-boot", "system-design", "typescript")))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].name").value("Java"))
                .andExpect(jsonPath("$[0].category").value("LANGUAGE"))
                .andExpect(jsonPath("$[0].description").isNotEmpty())
                .andExpect(jsonPath("$[0].active").doesNotExist());
    }

    @Test
    void theSeededCatalogReturns41TopicsInTotalAndEveryTopicHasAValidDifficultyRange() throws Exception {
        List<String> slugs = List.of("java", "postgresql", "react", "spring-boot", "system-design", "typescript", "javascript", "rest-api",
                "microservices");
        int total = 0;
        for (String slug : slugs) {
            MvcResult result = mockMvc.perform(get(BASE + "/skills/" + slug + "/topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].minDifficulty").isNotEmpty())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            total += JsonPath.<List<Object>>read(body, "$").size();
            List<Integer> mins = JsonPath.read(body, "$[*].minDifficulty");
            List<Integer> maxs = JsonPath.read(body, "$[*].maxDifficulty");
            for (int i = 0; i < mins.size(); i++) {
                assertThat(mins.get(i)).as(slug + " topic " + i).isBetween(1, 5).isLessThanOrEqualTo(maxs.get(i));
                assertThat(maxs.get(i)).isBetween(1, 5);
            }
        }
        assertThat(total).isEqualTo(41);
    }

    @Test
    void aTopicDocumentHasSlugNameDescriptionAndRangeOnly() throws Exception {
        mockMvc.perform(get(BASE + "/skills/spring-boot/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[?(@.slug=='transactions')].name").value(contains("Transactions")))
                .andExpect(jsonPath("$[?(@.slug=='transactions')].minDifficulty").value(contains(2)))
                .andExpect(jsonPath("$[?(@.slug=='transactions')].maxDifficulty").value(contains(5)))
                .andExpect(jsonPath("$[?(@.slug=='transactions')].description").value(contains(
                        "Declarative transactions, propagation, isolation, rollback rules and proxy pitfalls.")))
                .andExpect(jsonPath("$[0].weight").doesNotExist())
                .andExpect(jsonPath("$[0].skillId").doesNotExist())
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/options", "/skills", "/skills/java/topics"})
    void everySuccessfulCatalogAnswerIsPubliclyCacheableAndTagged(String path) throws Exception {
        mockMvc.perform(get(BASE + path))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=3600"))
                .andExpect(header().exists("ETag"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/options", "/skills", "/skills/java/topics"})
    void revalidatingWithTheEtagAnswers304(String path) throws Exception {
        String etag = mockMvc.perform(get(BASE + path)).andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(get(BASE + path).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
        mockMvc.perform(get(BASE + path).header("If-None-Match", "\"some-other-version\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etag));
    }

    @Test
    void theEtagChangesWhenTheCatalogChanges() throws Exception {
        String before = mockMvc.perform(get(BASE + "/skills")).andReturn().getResponse().getHeader("ETag");

        data.skill("brand-new-skill", "Brand New Skill", true);

        MvcResult after = mockMvc.perform(get(BASE + "/skills").header("If-None-Match", before))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(10)))
                .andReturn();
        assertThat(after.getResponse().getHeader("ETag")).isNotEqualTo(before);
    }

    @Test
    void aTokenOfAnyKindIsIgnoredOnTheCatalog() throws Exception {
        mockMvc.perform(get(BASE + "/skills").header("Authorization", "Bearer not-a-token")).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/skills").header("Authorization", "Basic Zm9vOmJhcg==")).andExpect(status().isOk());
    }

    @Test
    void anInactiveSkillIsAbsentFromTheListAndItsTopicsAre404() throws Exception {
        long retired = data.skill("retired-skill", "Retired Skill", false);
        data.topic(retired, "old-topic", "Old topic", 1, 2, 1.0);
        data.skill("active-extra", "Active Extra", true);

        mockMvc.perform(get(BASE + "/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", hasItem("active-extra")))
                .andExpect(jsonPath("$[*].slug", not(hasItem("retired-skill"))));
        mockMvc.perform(get(BASE + "/skills/retired-skill/topics"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aSkillWithoutTopicsHasAnEmptyTopicList() throws Exception {
        data.skill("topicless", "Topicless", true);

        mockMvc.perform(get(BASE + "/skills/topicless/topics")).andExpect(status().isOk()).andExpect(content().json("[]", JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cobol", "JAVA", "Java", "java ", " ", "spring", "€", "java.x"})
    void unknownSlugsAre404ProblemsThatAreNeitherCachedNorTagged(String slug) throws Exception {
        mockMvc.perform(get(BASE + "/skills/{slug}/topics", slug))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://techinterviewer.dev/problems/not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", not(containsString("public"))));
    }

    @Test
    void aVeryLongSlugIsA404NotAServerError() throws Exception {
        mockMvc.perform(get(BASE + "/skills/" + "a".repeat(5000) + "/topics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anUnknownCatalogPathIsA404Problem() throws Exception {
        mockMvc.perform(get(BASE + "/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
