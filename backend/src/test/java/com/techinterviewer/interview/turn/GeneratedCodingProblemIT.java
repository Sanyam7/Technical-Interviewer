package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.coding.support.EmptyProblemBankConfiguration;
import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.port.GeneratedQuestion;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * When the problem bank has no problem at the decided level, the question generator writes the coding problem, as before
 * the bank existed: its material is kept, it has no tests, and the candidate sees it without the key points.
 */
@Import(EmptyProblemBankConfiguration.class)
class GeneratedCodingProblemIT extends TurnApiTestBase {

    private static GeneratedQuestion question(String prompt, QuestionOrigin origin, int difficulty, String topic, List<UUID> chunkIds,
            QuestionDetails details) {
        return new GeneratedQuestion(prompt, List.of(new ExpectedKeyPoint("a", "first point", List.of()), new ExpectedKeyPoint("b", "second point", List.of())),
                details, origin, difficulty, topic, 7, chunkIds, "scripted-question", "2.1", "scripted-model", null);
    }

    @Test
    void aGeneratedCodingQuestionKeepsItsMaterialHasNoTestsAndTheCandidateSeesItWithoutTheKeyPoints() throws Exception {
        UUID ada = newUser();
        UUID id = startedInterview(ada, FULL_LOOP_CONFIGURATION);
        JsonNode current = currentQuestion(ada, id);
        generator.script(request -> question("Reverse a list", QuestionOrigin.GENERATED, request.targetDifficulty(),
                request.topic() == null ? null : request.topic().key(), List.of(),
                request.kind() == QuestionKind.CODING
                        ? new QuestionDetails(List.of("n <= 10"), List.of(new CodingExample("[1,2]", "[2,1]", "reversed")), "List<Integer> reverse(List<Integer> xs)", "java")
                        : null));
        evaluator.quality(Quality.INCORRECT);
        while (!current.get("answerFormat").asString().equals("CODE")) {
            clock.advance(Duration.ofSeconds(10));
            current = answerOk(ada, id, UUID.fromString(current.get("id").asString()), "text").get("nextQuestion");
        }

        JsonNode details = current.get("details");
        assertThat(details.get("language").asString()).isEqualTo("java");
        assertThat(details.get("constraints").get(0).asString()).isEqualTo("n <= 10");
        assertThat(details.get("examples").get(0).get("output").asString()).isEqualTo("[2,1]");
        assertThat(details.get("starterCode").asString()).isEqualTo("List<Integer> reverse(List<Integer> xs)");
        assertThat(details.get("testable").asBoolean()).isFalse();
        assertThat(details.get("title").isNull()).isTrue();
        assertThat(generator.requests()).anyMatch(request -> request.kind() == QuestionKind.CODING);
        assertThat(jdbc.sql("SELECT details ->> 'starterSignature' FROM questions WHERE id = :q").param("q", UUID.fromString(current.get("id").asString()))
                .query(String.class).single()).isEqualTo("List<Integer> reverse(List<Integer> xs)");
    }
}
