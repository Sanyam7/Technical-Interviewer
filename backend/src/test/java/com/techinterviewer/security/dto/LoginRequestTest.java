package com.techinterviewer.security.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoginRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> invalidFields(LoginRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void theEmailIsTrimmedAndLowerCased() {
        assertThat(new LoginRequest("  ADA@Example.com ", "x").email()).isEqualTo("ada@example.com");
    }

    @Test
    void anAddressThatIsNotAnEmailIsNotRejectedHereBecauseTheAnswerMustBeTheGenericOne() {
        assertThat(invalidFields(new LoginRequest("not an email", "whatever"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void aBlankEmailIsRejected(String email) {
        assertThat(invalidFields(new LoginRequest(email, "whatever"))).containsExactly("email");
    }

    @Test
    void aMissingEmailOrPasswordIsRejected() {
        assertThat(invalidFields(new LoginRequest(null, "whatever"))).containsExactly("email");
        assertThat(invalidFields(new LoginRequest("ada@example.com", null))).containsExactly("password");
    }

    @Test
    void anEmptyPasswordIsRejectedButAShortOneIsLeftToTheCredentialCheck() {
        assertThat(invalidFields(new LoginRequest("ada@example.com", ""))).containsExactly("password");
        assertThat(invalidFields(new LoginRequest("ada@example.com", "x"))).isEmpty();
    }

    @Test
    void aPasswordLongerThanBcryptReadsIsRejectedAtTheByteLimit() {
        assertThat(invalidFields(new LoginRequest("ada@example.com", "p".repeat(72)))).isEmpty();
        assertThat(invalidFields(new LoginRequest("ada@example.com", "p".repeat(73)))).containsExactly("password");
        assertThat(invalidFields(new LoginRequest("ada@example.com", "é".repeat(36)))).isEmpty();
        assertThat(invalidFields(new LoginRequest("ada@example.com", "é".repeat(37)))).containsExactly("password");
    }

    @Test
    void anOverlongEmailIsRejected() {
        assertThat(invalidFields(new LoginRequest("a".repeat(255), "x"))).containsExactly("email");
    }

    @Test
    void toStringNeverContainsThePassword() {
        assertThat(new LoginRequest("ada@example.com", "s3cret-value").toString()).doesNotContain("s3cret-value");
    }
}
