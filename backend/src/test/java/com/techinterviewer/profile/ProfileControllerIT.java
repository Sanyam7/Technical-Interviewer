package com.techinterviewer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.user.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The profile endpoints through the whole application: real tokens, real filter chain, real database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileControllerIT {

    private static final String URL = "/api/v1/profile";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    ProfileTestUsers users;
    TestAuth auth;
    UUID ada;
    UUID grace;

    @BeforeEach
    void setUp() {
        users = new ProfileTestUsers(jdbc);
        auth = TestAuth.forContext(context);
        ada = users.create();
        grace = users.create();
    }

    @AfterEach
    void tearDown() {
        users.cleanUp();
    }

    private RequestPostProcessor as(UUID user) {
        return auth.asUser(user, Role.CANDIDATE);
    }

    private ResultActions putAs(UUID user, String body) throws Exception {
        return mockMvc.perform(put(URL)
                .with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long profileCount(UUID user) {
        return jdbc.sql("SELECT count(*) FROM candidate_profiles WHERE user_id = :u").param("u", user).query(Long.class).single();
    }

    // ---- authentication -----------------------------------------------------------------------------------

    @Test
    void withoutATokenProfileRequestsAreUnauthorizedAndCreateNothing() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().exists("WWW-Authenticate"));
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"skills\":[]}")).andExpect(status().isUnauthorized());

        assertThat(jdbc.sql("SELECT count(*) FROM candidate_profiles").query(Long.class).single()).isZero();
    }

    @Test
    void anExpiredTokenIsRefusedAndTheDatabaseIsUntouched() throws Exception {
        String token = auth.expiredToken(ada, Role.CANDIDATE);

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        mockMvc.perform(put(URL).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"skills\":[]}"))
                .andExpect(status().isUnauthorized());

        assertThat(profileCount(ada)).isZero();
    }

    @Test
    void forgedTokensAreRefused() throws Exception {
        for (String token : List.of(
                auth.tamperedSignatureToken(ada, Role.CANDIDATE),
                auth.algNoneToken(ada, Role.CANDIDATE),
                auth.otherKeyToken(ada, Role.CANDIDATE),
                auth.withoutSubjectToken(Role.CANDIDATE))) {
            mockMvc.perform(get(URL).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        assertThat(profileCount(ada)).isZero();
    }

    // ---- behaviour -------------------------------------------------------------------------------------------

    @Test
    void theFirstGetCreatesTheProfileEmpty() throws Exception {
        mockMvc.perform(get(URL).with(as(ada)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.headline").value((Object) null))
                .andExpect(jsonPath("$.yearsExperience").value((Object) null))
                .andExpect(jsonPath("$.jobTitle").value((Object) null))
                .andExpect(jsonPath("$.skills", hasSize(0)))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(profileCount(ada)).isEqualTo(1);
    }

    @Test
    void whatWasPutIsReturnedByTheNextGetWithSkillSlugNameAndRating() throws Exception {
        putAs(ada, """
                {"headline":"Backend engineer","yearsExperience":7,"jobTitle":"Staff Engineer",
                 "skills":[{"skill":"spring-boot","selfRating":5},{"skill":"java","selfRating":4}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].skill").value("java"))
                .andExpect(jsonPath("$.skills[1].skill").value("spring-boot"));

        mockMvc.perform(get(URL).with(as(ada)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Backend engineer"))
                .andExpect(jsonPath("$.yearsExperience").value(7))
                .andExpect(jsonPath("$.jobTitle").value("Staff Engineer"))
                .andExpect(jsonPath("$.skills", hasSize(2)))
                .andExpect(jsonPath("$.skills[0].skill").value("java"))
                .andExpect(jsonPath("$.skills[0].name").value("Java"))
                .andExpect(jsonPath("$.skills[0].selfRating").value(4))
                .andExpect(jsonPath("$.skills[1].skill").value("spring-boot"))
                .andExpect(jsonPath("$.skills[1].name").value("Spring Boot"))
                .andExpect(jsonPath("$.skills[1].selfRating").value(5))
                .andExpect(jsonPath("$.skills[0].id").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.resumeDocumentId").doesNotExist());
    }

    @Test
    void aSecondPutReplacesTheFirstCompletely() throws Exception {
        putAs(ada, "{\"headline\":\"one\",\"yearsExperience\":3,\"jobTitle\":\"a\",\"skills\":[{\"skill\":\"java\",\"selfRating\":2}]}")
                .andExpect(status().isOk());

        putAs(ada, "{\"skills\":[{\"skill\":\"react\",\"selfRating\":5}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value((Object) null))
                .andExpect(jsonPath("$.yearsExperience").value((Object) null))
                .andExpect(jsonPath("$.skills", hasSize(1)))
                .andExpect(jsonPath("$.skills[0].skill").value("react"));
        assertThat(profileCount(ada)).isEqualTo(1);
    }

    // ---- ownership -------------------------------------------------------------------------------------------

    @Test
    void twoUsersWithInterleavedRequestsOnlyEverSeeAndChangeTheirOwnProfile() throws Exception {
        putAs(ada, "{\"headline\":\"Ada\",\"skills\":[{\"skill\":\"java\",\"selfRating\":5}]}").andExpect(status().isOk());
        mockMvc.perform(get(URL).with(as(grace)))
                .andExpect(jsonPath("$.headline").value((Object) null))
                .andExpect(jsonPath("$.skills", hasSize(0)));
        putAs(grace, "{\"headline\":\"Grace\",\"skills\":[{\"skill\":\"react\",\"selfRating\":1}]}").andExpect(status().isOk());
        mockMvc.perform(get(URL).with(as(ada)))
                .andExpect(jsonPath("$.headline").value("Ada"))
                .andExpect(jsonPath("$.skills[0].skill").value("java"))
                .andExpect(jsonPath("$.skills", hasSize(1)));
        putAs(ada, "{\"headline\":\"Ada v2\",\"skills\":[]}").andExpect(status().isOk());
        mockMvc.perform(get(URL).with(as(grace)))
                .andExpect(jsonPath("$.headline").value("Grace"))
                .andExpect(jsonPath("$.skills[0].skill").value("react"))
                .andExpect(jsonPath("$.skills[0].selfRating").value(1));
        mockMvc.perform(get(URL).with(as(ada)))
                .andExpect(jsonPath("$.headline").value("Ada v2"))
                .andExpect(jsonPath("$.skills", hasSize(0)));
    }

    @Test
    void anAdminHasAProfileOfItsOwnAndNothingMore() throws Exception {
        UUID admin = users.create();
        putAs(ada, "{\"headline\":\"Ada\",\"skills\":[]}").andExpect(status().isOk());

        mockMvc.perform(put(URL).with(auth.asUser(admin, Role.ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"headline\":\"Admin\",\"skills\":[]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL).with(auth.asUser(admin, Role.ADMIN))).andExpect(jsonPath("$.headline").value("Admin"));
        mockMvc.perform(get(URL).with(as(ada))).andExpect(jsonPath("$.headline").value("Ada"));
    }

    @Test
    void thereIsNoWayToAddressAnotherProfile() throws Exception {
        putAs(grace, "{\"headline\":\"Grace\",\"skills\":[]}").andExpect(status().isOk());

        mockMvc.perform(get(URL + "/" + grace).with(as(ada))).andExpect(status().isNotFound());
        mockMvc.perform(get(URL).param("userId", grace.toString()).with(as(ada))).andExpect(jsonPath("$.headline").value((Object) null));
        putAs(ada, "{\"userId\":\"" + grace + "\",\"headline\":\"hijack\",\"skills\":[]}").andExpect(status().isBadRequest());

        mockMvc.perform(get(URL).with(as(grace))).andExpect(jsonPath("$.headline").value("Grace"));
    }

    // ---- validation through the stack ----------------------------------------------------------------------

    @Test
    void anUnknownSkillIsA400OnItsFieldAndTheStoredProfileIsUntouched() throws Exception {
        putAs(ada, "{\"headline\":\"keep\",\"skills\":[{\"skill\":\"java\",\"selfRating\":3}]}").andExpect(status().isOk());

        putAs(ada, "{\"headline\":\"lost\",\"skills\":[{\"skill\":\"react\",\"selfRating\":3},{\"skill\":\"cobol\",\"selfRating\":3}]}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("skills[1].skill"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown or inactive skill 'cobol'."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(get(URL).with(as(ada)))
                .andExpect(jsonPath("$.headline").value("keep"))
                .andExpect(jsonPath("$.skills[0].skill").value("java"));
    }

    @Test
    void aSkillListedTwiceIsA400OnTheRepeatedEntry() throws Exception {
        putAs(ada, "{\"skills\":[{\"skill\":\"java\",\"selfRating\":3},{\"skill\":\"java\",\"selfRating\":4}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("skills[1].skill"));
    }

    @Test
    void annotationViolationsAreFieldLevelThroughTheRealStackToo() throws Exception {
        putAs(ada, "{\"yearsExperience\":61,\"skills\":[{\"skill\":\"java\",\"selfRating\":6}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='yearsExperience')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='skills[0].selfRating')]").isNotEmpty());
        assertThat(profileCount(ada)).isZero();
    }

    @Test
    void aValidTokenOfAnAccountThatNoLongerExistsIsAConflictAndCreatesNothing() throws Exception {
        UUID ghost = UuidV7.generate();

        mockMvc.perform(get(URL).with(as(ghost)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(profileCount(ghost)).isZero();
    }
}
