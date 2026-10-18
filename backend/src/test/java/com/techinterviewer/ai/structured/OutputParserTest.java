package com.techinterviewer.ai.structured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.agents.output.AssessmentDraft;
import com.techinterviewer.ai.provider.FinishReason;
import com.techinterviewer.ai.provider.LlmResponse;
import com.techinterviewer.ai.provider.TokenUsage;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.evaluation.domain.Criterion;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * The malformed-output fixtures of AI_ARCHITECTURE.md section 5: every shape a model may return either becomes a
 * valid value or a list of problems a repair prompt can show, never an exception of another kind and never a guess.
 */
class OutputParserTest {

    private final OutputParser parser = new OutputParser(TestJson.applicationMapper(), AiStack.validator());

    private static final String VALID = """
            {"keyPoints":[{"keyPointId":"kp1","status":"COVERED","evidenceQuote":"uses a lock"}],
             "factualErrors":[],
             "ratings":{"CORRECTNESS":{"rationale":"right","rating":3}},
             "candidateClaims":[],
             "summary":"Solid answer.",
             "flags":[]}""";

    private static LlmResponse answer(String text) {
        return answer(FinishReason.STOP, text);
    }

    private static LlmResponse answer(FinishReason finish, String text) {
        return new LlmResponse(text, finish, TokenUsage.UNKNOWN, null, Duration.ZERO);
    }

    private AssessmentDraft parse(String text) {
        return parser.parse(answer(text), AssessmentDraft.class);
    }

    private static String problems(Runnable parse) {
        try {
            parse.run();
        } catch (OutputRejectedException rejected) {
            return String.join(" | ", rejected.problems());
        }
        throw new AssertionError("the output was accepted");
    }

    @Test
    void aValidAnswerIsBoundToTheRecord() {
        AssessmentDraft draft = parse(VALID);

        assertThat(draft.ratings().get(Criterion.CORRECTNESS).rating()).isEqualTo(3);
        assertThat(draft.keyPoints().getFirst().evidenceQuote()).isEqualTo("uses a lock");
    }

    @Test
    void jsonInAMarkdownFenceOrSurroundedByProseIsRead() {
        assertThat(parse("```json\n" + VALID + "\n```").summary()).isEqualTo("Solid answer.");
        assertThat(parse("Here is my assessment:\n" + VALID + "\nI hope this helps!").summary()).isEqualTo("Solid answer.");
    }

    @Test
    void anAnswerCutOffByTheTokenLimitIsRejectedEvenIfItHappensToParse() {
        assertThatThrownBy(() -> parser.parse(answer(FinishReason.MAX_TOKENS, VALID), AssessmentDraft.class))
                .isInstanceOfSatisfying(OutputRejectedException.class,
                        rejected -> assertThat(rejected.problems().getFirst()).contains("cut off").contains("concisely"));
    }

    @Test
    void emptyTextAndTextWithoutAnObjectAreRejected() {
        assertThat(problems(() -> parse("   "))).contains("empty");
        assertThat(problems(() -> parse("I cannot grade this answer."))).contains("no JSON object");
        assertThat(problems(() -> parse("[1, 2, 3]"))).contains("no JSON object");
    }

    @Test
    void truncatedOrMalformedJsonIsRejected() {
        assertThat(problems(() -> parse(VALID.substring(0, VALID.length() / 2)))).startsWith("the answer is not valid JSON");
        assertThat(problems(() -> parse("{\"summary\": 'single quotes'}"))).startsWith("the answer is not valid JSON");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "a missing required field           | \"summary\":\"Solid answer.\",                     | ''                                      | summary",
        "an unknown field                   | \"flags\":[]                                       | \"flags\":[],\"score\":95               | score",
        "an unknown enum constant           | \"status\":\"COVERED\"                             | \"status\":\"MOSTLY\"                   | MOSTLY",
        "a rating out of range              | \"rating\":3                                       | \"rating\":7                            | rating",
        "a number where text is expected    | \"summary\":\"Solid answer.\"                      | \"summary\":42                          | summary",
        "text where a number is expected    | \"rating\":3                                       | \"rating\":\"3\"                        | rating",
        "a fraction where an integer is due | \"rating\":3                                       | \"rating\":3.5                          | rating",
        "an unknown criterion               | \"CORRECTNESS\"                                    | \"ELEGANCE\"                            | ELEGANCE",
        "a blank summary                    | \"summary\":\"Solid answer.\"                      | \"summary\":\"  \"                      | summary",
        "null where a list is required      | \"factualErrors\":[]                               | \"factualErrors\":null                  | factualErrors"
    })
    void aWrongShapeIsRejectedWithAProblemNamingTheField(String name, String replace, String with, String mentioned) {
        String text = VALID.replace(replace, with);
        assertThat(text).as("the fixture changed").isNotEqualTo(VALID);

        assertThat(problems(() -> parse(text))).contains(mentioned);
    }

    @Test
    void everyViolationIsReportedNotOnlyTheFirst() {
        String text = VALID.replace("\"summary\":\"Solid answer.\"", "\"summary\":\"\"").replace("\"rationale\":\"right\"", "\"rationale\":\"\"");

        assertThatThrownBy(() -> parse(text)).isInstanceOfSatisfying(OutputRejectedException.class,
                rejected -> assertThat(rejected.problems()).hasSize(2));
    }

    @Test
    void aProblemIsShortEnoughForARepairPrompt() {
        String hugeField = "\"" + "x".repeat(5000) + "\":1,";
        assertThat(problems(() -> parse(VALID.replace("{\"keyPoints\"", "{" + hugeField + "\"keyPoints\""))).length())
                .isLessThanOrEqualTo(OutputParser.MAX_PROBLEM_LENGTH + 100);
    }
}
