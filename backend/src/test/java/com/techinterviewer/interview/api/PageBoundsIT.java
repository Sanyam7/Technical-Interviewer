package com.techinterviewer.interview.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A page number so large that its row offset overflows must be a validation error, not a 500: both list endpoints cap
 * the page number, so the offset stays far below the integer range even at the largest page size.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PageBoundsIT {

    @Autowired
    MockMvc mockMvc;

    private static final String[] LISTS = {"/api/v1/interviews", "/api/v1/interview-configurations"};

    @ParameterizedTest
    @ValueSource(strings = {"2147483647", "1073741823", "21474837", "10001", "-1"})
    void anExcessiveOrNegativePageIsAValidationError(String page) throws Exception {
        for (String list : LISTS) {
            mockMvc.perform(get(list).param("page", page).param("size", "100")
                            .with(TestAuth.bearer(UUID.randomUUID(), Role.CANDIDATE)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void theLargestAllowedPageWithTheLargestSizeIsAnEmptyPage() throws Exception {
        for (String list : LISTS) {
            mockMvc.perform(get(list).param("page", "10000").param("size", "100")
                            .with(TestAuth.bearer(UUID.randomUUID(), Role.CANDIDATE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.page").value(10000));
        }
    }
}
