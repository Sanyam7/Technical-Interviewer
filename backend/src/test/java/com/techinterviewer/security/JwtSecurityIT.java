package com.techinterviewer.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The same rules as {@link JwtAuthenticationTest}, once, through the complete application context. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(SecurityTestController.PROFILE)
@Import(TestcontainersConfiguration.class)
class JwtSecurityIT {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Test
    void aTokenIssuedByTheApplicationOpensSecuredEndpointsAndIdentifiesTheCaller() throws Exception {
        TestAuth auth = TestAuth.forContext(context);

        mockMvc.perform(get("/api/v1/test/me").with(auth.asUser(USER, Role.CANDIDATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER.toString()))
                .andExpect(jsonPath("$.role").value("CANDIDATE"));
    }

    @Test
    void adminEndpointsNeedTheAdminRole() throws Exception {
        TestAuth auth = TestAuth.forContext(context);

        mockMvc.perform(get("/api/v1/admin/test")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/test").with(auth.asUser(USER, Role.CANDIDATE))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/test").with(auth.asUser(USER, Role.ADMIN))).andExpect(status().isOk());
    }

    @Test
    void expiredAndForgedTokensAreRefusedByTheRealFilterChain() throws Exception {
        TestAuth auth = TestAuth.forContext(context);

        mockMvc.perform(get("/api/v1/test/secured").header("Authorization", "Bearer " + auth.expiredToken(USER, Role.ADMIN)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        mockMvc.perform(get("/api/v1/test/secured").header("Authorization", "Bearer " + auth.algNoneToken(USER, Role.ADMIN)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/admin/test").header("Authorization", "Bearer " + auth.roleEscalatedToken(USER)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/test/secured").header("Authorization", "Bearer " + auth.otherKeyToken(USER, Role.ADMIN)))
                .andExpect(status().isUnauthorized());
    }
}
