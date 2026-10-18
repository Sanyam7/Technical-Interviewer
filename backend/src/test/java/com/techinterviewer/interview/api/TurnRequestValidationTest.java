package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.api.dto.AnswerRequest;
import com.techinterviewer.interview.api.dto.FinishReason;
import com.techinterviewer.interview.api.dto.FinishRequest;
import com.techinterviewer.interview.api.dto.SkipRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The shape rules of the three request bodies of a turn; the rules that depend on the question are the service's. */
class TurnRequestValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final UUID QUESTION = UUID.randomUUID();

    private static Set<String> violations(Object request) {
        return VALIDATOR.validate(request).stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void aWellFormedAnswerHasNoViolation() {
        assertThat(violations(new AnswerRequest(QUESTION, "my answer", null, null))).isEmpty();
        assertThat(violations(new AnswerRequest(QUESTION, "x".repeat(20_000), "java", 0))).isEmpty();
        assertThat(violations(new AnswerRequest(QUESTION, "a", "java", 86_400))).isEmpty();
    }

    @Test
    void theQuestionAndTheContentAreRequired() {
        assertThat(violations(new AnswerRequest(null, "my answer", null, null))).containsExactly("questionId");
        assertThat(violations(new AnswerRequest(QUESTION, null, null, null))).containsExactly("content");
        assertThat(violations(new AnswerRequest(QUESTION, "", null, null))).containsExactly("content");
        assertThat(violations(new AnswerRequest(QUESTION, " \n\t ", null, null))).containsExactly("content");
    }

    @Test
    void noAnswerMayExceedTheLongestLimitOfAnyQuestion() {
        assertThat(violations(new AnswerRequest(QUESTION, "x".repeat(20_001), null, null))).containsExactly("content");
        assertThat(AnswerRequest.MAX_TEXT_CONTENT).isEqualTo(8_000);
        assertThat(AnswerRequest.MAX_CODE_CONTENT).isEqualTo(20_000);
    }

    @Test
    void theLanguageAndTheTimeAreBounded() {
        assertThat(violations(new AnswerRequest(QUESTION, "a", "x".repeat(21), null))).containsExactly("codeLanguage");
        assertThat(violations(new AnswerRequest(QUESTION, "a", null, -1))).containsExactly("timeTakenSeconds");
        assertThat(violations(new AnswerRequest(QUESTION, "a", null, 86_401))).containsExactly("timeTakenSeconds");
    }

    @Test
    void aSkipMayCarryOnlyASensibleTime() {
        assertThat(violations(new SkipRequest(null))).isEmpty();
        assertThat(violations(new SkipRequest(0))).isEmpty();
        assertThat(violations(new SkipRequest(-1))).containsExactly("timeTakenSeconds");
        assertThat(violations(new SkipRequest(86_401))).containsExactly("timeTakenSeconds");
    }

    @Test
    void aFinishNeedsItsReason() {
        assertThat(violations(new FinishRequest(null))).containsExactly("reason");
        assertThat(violations(new FinishRequest(FinishReason.CANDIDATE_ENDED))).isEmpty();
        assertThat(violations(new FinishRequest(FinishReason.ACKNOWLEDGE_COMPLETE))).isEmpty();
    }
}
