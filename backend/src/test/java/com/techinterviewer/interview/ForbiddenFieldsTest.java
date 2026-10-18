package com.techinterviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The scanner of the contract test must itself be able to find what it looks for: it is tested on documents that contain it. */
class ForbiddenFieldsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void aCleanQuestionViewHasNothingToFind() {
        JsonNode view = json("""
                {"id":"1","sequenceNo":1,"kind":"CODING","isFollowUp":false,"section":"CODING","topic":null,
                 "prompt":"p","answerFormat":"CODE","suggestedMinutes":10,"answered":false,
                 "details":{"language":"java","constraints":["c"],"examples":[{"input":"i","output":"o","explanation":null}],"starterCode":"s"}}""");

        assertThat(ForbiddenFields.find(view, false)).isEmpty();
        assertThat(ForbiddenFields.find(view, true)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "expectedKeyPoints", "rubricId", "origin", "memory", "topicQuotas", "pendingStep", "slots", "llmCallId", "EXPECTEDKEYPOINTS", "level",
            "assessment", "evaluation", "evaluationId", "followUpMode", "targetKeyPointId", "targetClaim", "parentQuestionId", "followUpDepth"})
    void serverSideNamesAreFoundAtAnyDepthAndEvenInsideAllowedFeedback(String name) {
        JsonNode nested = json("{\"a\":[{\"b\":{\"" + name + "\":1}}]}");
        JsonNode inFeedback = json("{\"entries\":[{\"feedback\":{\"" + name + "\":1}}]}");

        assertThat(ForbiddenFields.find(nested, false)).containsExactly("$.a[0].b." + name);
        assertThat(ForbiddenFields.find(inFeedback, true)).containsExactly("$.entries[0].feedback." + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"score", "overallScore", "rating", "criteria", "keyPoints", "recommendation", "confidence"})
    void scoresAreFoundOutsideFeedbackAndEvenWhenFeedbackIsAllowed(String name) {
        JsonNode document = json("{\"question\":{\"" + name + "\":1}}");

        assertThat(ForbiddenFields.find(document, false)).containsExactly("$.question." + name);
        assertThat(ForbiddenFields.find(document, true)).containsExactly("$.question." + name);
    }

    @Test
    void feedbackIsOnlyAllowedWhenTheInterviewIsCompleted() {
        JsonNode completed = json("""
                {"entries":[{"feedback":{"overallScore":57.5,"criteria":[{"criterion":"CORRECTNESS","rating":3}],
                 "keyPoints":[{"text":"t","status":"COVERED"}],"summary":"s"}}]}""");
        JsonNode pending = json("{\"entries\":[{\"feedback\":null}]}");

        assertThat(ForbiddenFields.find(completed, true)).isEmpty();
        assertThat(ForbiddenFields.find(completed, false)).containsExactly("$.entries[0].feedback");
        assertThat(ForbiddenFields.find(pending, false)).isEmpty();
    }

    @Test
    void difficultyIsOnlyAllowedAsTheRequestedDifficultyOfAConfiguration() {
        JsonNode echo = json("""
                {"configuration":{"experienceLevel":"MID","difficulty":"MEDIUM"},
                 "items":[{"configuration":{"experienceLevel":"MID","difficulty":"HARD"}}]}""");
        JsonNode root = json("{\"experienceLevel\":\"MID\",\"difficulty\":\"HARD\"}");
        JsonNode question = json("{\"currentQuestion\":{\"difficulty\":3}}");
        JsonNode plan = json("{\"plan\":{\"difficulty\":{\"start\":3}}}");

        assertThat(ForbiddenFields.find(echo, false)).isEmpty();
        assertThat(ForbiddenFields.find(root, false)).isEmpty();
        assertThat(ForbiddenFields.find(question, false)).containsExactly("$.currentQuestion.difficulty");
        assertThat(ForbiddenFields.find(plan, false)).containsExactly("$.plan.difficulty");
    }

    @Test
    void everyViolationIsReportedNotJustTheFirst() {
        JsonNode document = json("{\"origin\":\"GENERATED\",\"q\":{\"rubricId\":\"x\",\"score\":1}}");

        assertThat(ForbiddenFields.find(document, false)).containsExactlyInAnyOrder("$.origin", "$.q.rubricId", "$.q.score");
    }

    @Test
    void valuesAreNeverMistakenForPropertyNames() {
        JsonNode document = json("{\"prompt\":\"explain the rubric and the score\",\"tags\":[\"origin\",\"memory\"]}");

        assertThat(ForbiddenFields.find(document, false)).isEqualTo(List.of());
    }
}
