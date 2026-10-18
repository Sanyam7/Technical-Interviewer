package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Awkward input to the public and authenticated endpoints: it must be refused cleanly, never reach the database as a 409/500. */
@SpringBootTest(properties = {"app.rate-limit.auth-per-minute=1000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthInputHardeningIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanUsers() {
        jdbc.execute("truncate table users cascade");
    }

    private ResultActions register(String email, String password, String displayName) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"displayName\":\"" + displayName + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void aPasswordTypedDecomposedOnOneDeviceAndComposedOnAnotherIsTheSamePassword() throws Exception {
        String decomposed = "caf\\u0065\\u0301-au-lait-2026";   // e + combining acute accent
        String composed = "caf\\u00e9-au-lait-2026";             // precomposed é

        register("normal@example.com", decomposed, "Normal").andExpect(status().isCreated());

        login("normal@example.com", composed).andExpect(status().isOk());
        login("normal@example.com", decomposed).andExpect(status().isOk());
        login("normal@example.com", "cafe-au-lait-2026").andExpect(status().isUnauthorized());
    }

    @Test
    void theByteLimitIsAppliedToTheNormalisedPassword() throws Exception {
        // "xy" and 35 decomposed letters: 2 + 35 x (1 + 2) = 107 bytes as typed, but 2 + 35 x 2 = 72 bytes once composed.
        String decomposed = "xy" + "e\\u0301".repeat(35);

        register("limit@example.com", decomposed, "Limit").andExpect(status().isCreated());
    }

    @Test
    void anEmailWithAControlCharacterIsTheSameGeneric401AsAnUnknownAccountNotAConflict() throws Exception {
        login("nul\\u0000@example.com", "correct-horse-battery")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("a b@example.com", "correct-horse-battery").andExpect(status().isUnauthorized());
    }

    @Test
    void aDisplayNameWithABidiOverrideOrAnInvisibleSeparatorIsRefused() throws Exception {
        register("bidi@example.com", "correct-horse-battery", "Ada\\u202Eevil").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
        register("sep@example.com", "correct-horse-battery", "Ada\\u2028Lovelace").andExpect(status().isBadRequest());
        register("nul@example.com", "correct-horse-battery", "Ada\\u0000").andExpect(status().isBadRequest());
        register("ok@example.com", "correct-horse-battery", "Zo\\u00eb \\u5c71\\u7530").andExpect(status().isCreated());
    }

    @Test
    void controlCharactersInProfileTextAreAValidationErrorNotAConflict() throws Exception {
        UUID user = UUID.randomUUID();
        for (String body : new String[] {"{\"headline\":\"a\\u0000b\",\"skills\":[]}", "{\"jobTitle\":\"a\\nb\",\"skills\":[]}"}) {
            mockMvc.perform(put("/api/v1/profile").with(TestAuth.bearer(user, Role.CANDIDATE))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void aNulInAnAnswerIsAValidationErrorButTabsAndLineBreaksAreFine() throws Exception {
        UUID user = UUID.randomUUID();
        String path = "/api/v1/interviews/" + UUID.randomUUID() + "/answers";
        String question = UUID.randomUUID().toString();

        mockMvc.perform(post(path).with(TestAuth.bearer(user, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + question + "\",\"content\":\"abc\\u0000def\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        int multiline = mockMvc.perform(post(path).with(TestAuth.bearer(user, Role.CANDIDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"" + question + "\",\"content\":\"line one\\n\\tline two\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(multiline).as("passes validation and reaches the (missing) interview").isEqualTo(404);
    }
}
