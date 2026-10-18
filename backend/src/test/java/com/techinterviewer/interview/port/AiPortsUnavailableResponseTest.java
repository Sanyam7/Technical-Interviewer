package com.techinterviewer.interview.port;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The default port beans behind a real controller and the real exception handler: what a client sees without an AI layer. */
@WebMvcTest(AiProbeController.class)
@Import(DefaultAiPortsConfiguration.class)
@ActiveProfiles(AiProbeController.PROFILE)
class AiPortsUnavailableResponseTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void aRequestThatNeedsTheAiAnswersWith503AndAProblemDocument() throws Exception {
        mockMvc.perform(get("/test/ai/question").with(user("u")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.type").value("https://techinterviewer.dev/problems/ai-provider-unavailable"))
                .andExpect(jsonPath("$.detail").value("Question generation is unavailable: no AI provider is configured."))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
