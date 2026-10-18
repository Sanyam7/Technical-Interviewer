package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuestionContentTest {

    private static QuestionContent content(String prompt, List<ExpectedKeyPoint> points, String rubric, QuestionOrigin origin, UUID call) {
        return new QuestionContent(prompt, null, points, rubric, origin, call);
    }

    @Test
    void validContentIsKeptAndItsKeyPointsCannotChangeThroughTheCallersList() {
        List<ExpectedKeyPoint> points = new ArrayList<>(List.of(new ExpectedKeyPoint("kp1", "a", List.of())));
        UUID call = UUID.randomUUID();

        QuestionContent content = content("Explain.", points, "technical.v1", QuestionOrigin.EXEMPLAR_FALLBACK, call);
        points.add(new ExpectedKeyPoint("kp2", "b", List.of()));

        assertThat(content.prompt()).isEqualTo("Explain.");
        assertThat(content.expectedKeyPoints()).extracting(ExpectedKeyPoint::id).containsExactly("kp1");
        assertThat(content.rubricId()).isEqualTo("technical.v1");
        assertThat(content.origin()).isEqualTo(QuestionOrigin.EXEMPLAR_FALLBACK);
        assertThat(content.generationLlmCallId()).isEqualTo(call);
    }

    @Test
    void thePromptMustNotBeBlankOrLongerThanTheLimit() {
        assertThatIllegalArgumentException().isThrownBy(() -> content(" ", List.of(), "r", QuestionOrigin.STATIC, null)).withMessageContaining("prompt");
        assertThat(content("x".repeat(QuestionContent.MAX_PROMPT_LENGTH), List.of(), "r", QuestionOrigin.STATIC, null).prompt()).hasSize(10_000);
        assertThatIllegalArgumentException().isThrownBy(() -> content("x".repeat(10_001), List.of(), "r", QuestionOrigin.STATIC, null));
    }

    @Test
    void theRubricIdMustNotBeBlankOrLongerThanTheColumn() {
        assertThatIllegalArgumentException().isThrownBy(() -> content("p", List.of(), "", QuestionOrigin.STATIC, null)).withMessageContaining("rubricId");
        assertThat(content("p", List.of(), "r".repeat(40), QuestionOrigin.STATIC, null).rubricId()).hasSize(40);
        assertThatIllegalArgumentException().isThrownBy(() -> content("p", List.of(), "r".repeat(41), QuestionOrigin.STATIC, null));
    }

    @Test
    void theOriginIsRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> content("p", List.of(), "r", null, null)).withMessageContaining("origin");
    }

    @Test
    void keyPointIdsMustBeUnique() {
        List<ExpectedKeyPoint> twice = List.of(new ExpectedKeyPoint("kp1", "a", List.of()), new ExpectedKeyPoint("kp1", "b", List.of()));

        assertThatIllegalArgumentException().isThrownBy(() -> content("p", twice, "r", QuestionOrigin.GENERATED, null)).withMessageContaining("kp1");
    }

    @Test
    void atMostTwentyKeyPointsAreAllowed() {
        List<ExpectedKeyPoint> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(new ExpectedKeyPoint("kp" + i, "t", List.of()));
        }
        assertThat(content("p", twenty, "r", QuestionOrigin.GENERATED, null).expectedKeyPoints()).hasSize(20);

        List<ExpectedKeyPoint> twentyOne = new ArrayList<>(twenty);
        twentyOne.add(new ExpectedKeyPoint("kp20", "t", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> content("p", twentyOne, "r", QuestionOrigin.GENERATED, null)).withMessageContaining("20");
    }

    @Test
    void aStaticQuestionHasNoModelCall() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> content("p", List.of(), "r", QuestionOrigin.STATIC, UUID.randomUUID()))
                .withMessageContaining("static");
        assertThat(content("p", List.of(), "r", QuestionOrigin.GENERATED, null).generationLlmCallId()).isNull();
    }
}
