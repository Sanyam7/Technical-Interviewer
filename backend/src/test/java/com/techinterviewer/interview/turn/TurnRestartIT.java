package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TechInterviewerApplication;
import com.techinterviewer.TestClock;
import com.techinterviewer.TestClockConfiguration;
import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator;
import com.techinterviewer.evaluation.support.ScriptedQuestionGenerator;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.user.Role;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import tools.jackson.databind.JsonNode;

/**
 * A restart: the interview waits for its next question because the generator was down; a second, completely fresh
 * application (own beans, own ports, nothing shared but the database) recovers it, because everything the interview
 * knows lives in the database.
 */
class TurnRestartIT extends TurnApiTestBase {

    /** Keeps the test classes of this module out of the component scan of the second application. */
    private static final class ExcludeTestClasses extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return reader.getResource().getURL().toString().contains("/test-classes/");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ExcludeTestClasses;
        }

        @Override
        public int hashCode() {
            return ExcludeTestClasses.class.hashCode();
        }
    }

    private ConfigurableApplicationContext secondApplication() {
        JdbcConnectionDetails shared = context.getBean(JdbcConnectionDetails.class);
        return new SpringApplicationBuilder(TechInterviewerApplication.class, TestClockConfiguration.class, TurnTestPorts.class)
                .web(WebApplicationType.SERVLET)
                .initializers(application -> application.getBeanFactory().registerSingleton("excludeTestClasses", new ExcludeTestClasses()))
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + shared.getJdbcUrl(),
                        "--spring.datasource.username=" + shared.getUsername(),
                        "--spring.datasource.password=" + shared.getPassword(),
                        "--app.cors.allowed-origins=http://localhost:5173",
                        "--spring.main.banner-mode=off");
    }

    private static HttpResponse<String> post(ConfigurableApplicationContext app, String token, String path) throws Exception {
        String port = app.getEnvironment().getProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aFreshApplicationRecoversAnInterviewThatWaitedForItsNextQuestionAndCarriesItToTheEnd() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada);
        generator.unavailable();
        answerIntroduction(ada, id);
        var decided = generator.requests().getFirst();
        assertThat(session(ada, id).get("awaitingNextQuestion").asBoolean()).isTrue();

        try (ConfigurableApplicationContext second = secondApplication()) {
            second.getBean(TestClock.class).set(clock.instant());
            ScriptedQuestionGenerator freshGenerator = second.getBean(ScriptedQuestionGenerator.class);
            second.getBean(ScriptedAnswerEvaluator.class).quality(ScriptedAnswerEvaluator.Quality.AVERAGE);
            second.getBean(com.techinterviewer.evaluation.support.ScriptedReportNarrator.class).llmCallId(null);
            assertThat(freshGenerator.calls()).isZero();
            String token = TestAuth.forContext(second).token(ada, Role.CANDIDATE);

            HttpResponse<String> recovered = post(second, token, "/api/v1/interviews/" + id + "/questions/next");

            assertThat(recovered.statusCode()).isEqualTo(200);
            JsonNode session = json.readTree(recovered.body());
            assertThat(session.get("awaitingNextQuestion").asBoolean()).isFalse();
            assertThat(session.get("currentQuestion").get("kind").asString()).isEqualTo("TECHNICAL");
            assertThat(freshGenerator.calls()).isEqualTo(1);
            assertThat(freshGenerator.lastRequest().topic()).as("the decision made before the restart is the one carried out").isEqualTo(decided.topic());
            assertThat(freshGenerator.lastRequest().targetDifficulty()).isEqualTo(decided.targetDifficulty());
        }

        // The first application sees the recovered question and can carry on.
        generator.available();
        assertThat(session(ada, id).get("currentQuestion").get("sequenceNo").asInt()).isEqualTo(2);
        assertThat(answerCurrent(ada, id, "an answer").get("accepted").asBoolean()).isTrue();
        assertThat(count("questions", id)).isGreaterThanOrEqualTo(3);
    }
}
