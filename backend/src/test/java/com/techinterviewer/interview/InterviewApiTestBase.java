package com.techinterviewer.interview;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.TestClock;
import com.techinterviewer.TestClockConfiguration;
import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.interview.repository.InterviewStateTransitionRepository;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base of the interview API integration tests: the whole application against a real PostgreSQL, real bearer tokens
 * and a clock the test moves. Every test starts at {@link TestClock#START} and removes the users it created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
public abstract class InterviewApiTestBase {

    protected static final String API = "/api/v1";

    /** A TECHNICAL configuration of 45 minutes in Java and Spring Boot. */
    protected static final String TECHNICAL_CONFIGURATION = """
            {"role":"BACKEND","experienceLevel":"MID","difficulty":"MEDIUM","durationMinutes":45,
             "interviewType":"TECHNICAL","skills":["java","spring-boot"]}""";

    /** A FULL_LOOP configuration of 60 minutes in Java and PostgreSQL with a Java coding round. */
    protected static final String FULL_LOOP_CONFIGURATION = """
            {"role":"BACKEND","experienceLevel":"SENIOR","difficulty":"HARD","durationMinutes":60,
             "interviewType":"FULL_LOOP","skills":["java","postgresql"],"codingLanguage":"java"}""";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TestClock clock;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected TransactionTemplate transaction;

    @Autowired
    protected InterviewRepository interviews;

    @Autowired
    protected InterviewConfigurationRepository configurations;

    @Autowired
    protected InterviewQuestionRepository questions;

    @Autowired
    protected CandidateAnswerRepository answers;

    @Autowired
    protected EvaluationRepository evaluations;

    @Autowired
    protected InterviewStateTransitionRepository transitions;

    protected TestAuth auth;
    protected InterviewFixture fixture;
    protected CatalogRows catalog;

    @BeforeEach
    void prepareInterviewApiTest() {
        clock.set(TestClock.START);
        auth = TestAuth.forContext(context);
        fixture = new InterviewFixture(jdbc, transaction, interviews, configurations, questions, answers, evaluations, transitions);
        catalog = new CatalogRows(jdbc);
    }

    @AfterEach
    void cleanUpInterviewApiTest() {
        fixture.cleanUp();
        catalog.cleanUp();
    }

    protected UUID newUser() {
        return fixture.user();
    }

    protected RequestPostProcessor as(UUID user) {
        return auth.asUser(user, Role.CANDIDATE);
    }

    // ---- requests -----------------------------------------------------------------------------------------------

    protected ResultActions getAs(UUID user, String path) throws Exception {
        return mockMvc.perform(get(API + path).with(as(user)));
    }

    protected ResultActions postAs(UUID user, String path) throws Exception {
        return mockMvc.perform(post(API + path).with(as(user)));
    }

    protected ResultActions postAs(UUID user, String path, String body) throws Exception {
        return mockMvc.perform(post(API + path).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions deleteAs(UUID user, String path) throws Exception {
        return mockMvc.perform(delete(API + path).with(as(user)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** A JSON body made from {@code base} with one property replaced (or removed when {@code value} is null). */
    protected String with(String base, String property, Object value) {
        ObjectNode node = (ObjectNode) json.readTree(base);
        if (value == null) {
            node.remove(property);
        } else {
            node.set(property, json.valueToTree(value));
        }
        return node.toString();
    }

    // ---- flows ---------------------------------------------------------------------------------------------------

    /** Creates a TECHNICAL interview through the API and returns its id. */
    protected UUID createInterview(UUID user) throws Exception {
        return createInterview(user, TECHNICAL_CONFIGURATION);
    }

    protected UUID createInterview(UUID user, String configuration) throws Exception {
        ResultActions created = postAs(user, "/interviews", "{\"configuration\":" + configuration + "}").andExpect(status().isCreated());
        return UUID.fromString(body(created).get("id").asString());
    }

    protected UUID saveConfiguration(UUID user, String configuration) throws Exception {
        ResultActions created = postAs(user, "/interview-configurations", configuration).andExpect(status().isCreated());
        return UUID.fromString(body(created).get("id").asString());
    }

    /** Creates and starts a TECHNICAL interview through the API and returns its id. */
    protected UUID startedInterview(UUID user) throws Exception {
        UUID id = createInterview(user);
        postAs(user, "/interviews/" + id + "/start").andExpect(status().isOk());
        return id;
    }
}
