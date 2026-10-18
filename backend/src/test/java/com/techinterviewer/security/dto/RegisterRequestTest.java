package com.techinterviewer.security.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegisterRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String PASSWORD = "correct-horse-battery";

    private static Set<String> invalidFields(RegisterRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static RegisterRequest withPassword(String password) {
        return new RegisterRequest("ada@example.com", password, "Ada");
    }

    @Test
    void aTypicalRequestIsValid() {
        assertThat(VALIDATOR.validate(new RegisterRequest("ada@example.com", PASSWORD, "Ada"))).isEmpty();
    }

    // ---- password: UTF-8 bytes 10..72 ---------------------------------------------------------------------

    @ParameterizedTest(name = "{0} ASCII characters")
    @ValueSource(ints = {0, 1, 9})
    void aPasswordShorterThanTenBytesIsRejected(int length) {
        assertThat(invalidFields(withPassword("p".repeat(length)))).containsExactly("password");
    }

    @ParameterizedTest(name = "{0} ASCII characters")
    @ValueSource(ints = {10, 11, 71, 72})
    void aPasswordOfTenToSeventyTwoBytesIsAccepted(int length) {
        assertThat(invalidFields(withPassword("p".repeat(length)))).isEmpty();
    }

    @ParameterizedTest(name = "{0} ASCII characters")
    @ValueSource(ints = {73, 100, 5000})
    void aPasswordLongerThanSeventyTwoBytesIsRejected(int length) {
        assertThat(invalidFields(withPassword("p".repeat(length)))).containsExactly("password");
    }

    @Test
    void twoByteCharactersCountTwiceFiveOfThemAreExactlyTheMinimum() {
        assertThat(invalidFields(withPassword("é".repeat(4)))).as("8 bytes").containsExactly("password");
        assertThat(invalidFields(withPassword("é".repeat(5)))).as("10 bytes").isEmpty();
    }

    @Test
    void thirtySixTwoByteCharactersAreExactlyTheMaximumAndThirtySevenAreTooMany() {
        assertThat(invalidFields(withPassword("é".repeat(36)))).as("72 bytes").isEmpty();
        assertThat(invalidFields(withPassword("é".repeat(37)))).as("74 bytes").containsExactly("password");
    }

    @Test
    void fourByteEmojiAreCountedByTheirEncodedSize() {
        String key = "🔑";
        assertThat(invalidFields(withPassword(key.repeat(18)))).as("72 bytes, 36 chars").isEmpty();
        assertThat(invalidFields(withPassword(key.repeat(19)))).as("76 bytes, 38 chars").containsExactly("password");
        assertThat(invalidFields(withPassword(key.repeat(2)))).as("8 bytes").containsExactly("password");
        assertThat(invalidFields(withPassword(key.repeat(3)))).as("12 bytes").isEmpty();
    }

    @Test
    void aMissingPasswordIsRejectedWithoutFailingTheByteCheck() {
        Set<ConstraintViolation<RegisterRequest>> violations = VALIDATOR.validate(withPassword(null));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath()).hasToString("password");
    }

    @Test
    void theViolationMessageDoesNotRepeatThePassword() {
        String secret = "short-pw";
        String message = VALIDATOR.validate(withPassword(secret)).iterator().next().getMessage();

        assertThat(message).doesNotContain(secret).contains("10").contains("72");
    }

    // ---- email -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ada", "ada@", "@example.com", "ada@example", "ada @example.com", "ada@example.com,b@c.de"})
    void aMalformedEmailIsRejected(String email) {
        assertThat(invalidFields(new RegisterRequest(email, PASSWORD, "Ada"))).contains("email");
    }

    @Test
    void aMissingEmailIsRejected() {
        assertThat(invalidFields(new RegisterRequest(null, PASSWORD, "Ada"))).containsExactly("email");
    }

    @Test
    void anEmailOfExactly254CharactersIsAcceptedAndOneMoreIsNot() {
        String atLimit = "l".repeat(64) + "@" + "d".repeat(63) + "." + "e".repeat(63) + "." + "f".repeat(61);
        assertThat(atLimit).hasSize(254);

        assertThat(invalidFields(new RegisterRequest(atLimit, PASSWORD, "Ada"))).isEmpty();
        assertThat(invalidFields(new RegisterRequest(atLimit + "f", PASSWORD, "Ada"))).contains("email");
    }

    @Test
    void theEmailIsTrimmedAndLowerCasedWhenTheRequestIsBuilt() {
        RegisterRequest request = new RegisterRequest("  Ada@Example.COM  ", PASSWORD, "Ada");

        assertThat(request.email()).isEqualTo("ada@example.com");
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    // ---- display name ------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void aBlankDisplayNameIsRejected(String name) {
        assertThat(invalidFields(new RegisterRequest("ada@example.com", PASSWORD, name))).containsExactly("displayName");
    }

    @Test
    void aMissingDisplayNameIsRejected() {
        assertThat(invalidFields(new RegisterRequest("ada@example.com", PASSWORD, null))).containsExactly("displayName");
    }

    @Test
    void aDisplayNameOfOneToEightyCharactersIsAccepted() {
        assertThat(invalidFields(new RegisterRequest("ada@example.com", PASSWORD, "A"))).isEmpty();
        assertThat(invalidFields(new RegisterRequest("ada@example.com", PASSWORD, "A".repeat(80)))).isEmpty();
        assertThat(invalidFields(new RegisterRequest("ada@example.com", PASSWORD, "A".repeat(81)))).containsExactly("displayName");
    }

    @Test
    void theDisplayNameIsTrimmedBeforeItIsMeasured() {
        RegisterRequest request = new RegisterRequest("ada@example.com", PASSWORD, "  " + "A".repeat(80) + "  ");

        assertThat(request.displayName()).hasSize(80);
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ada\nLovelace", "Ada\u0000", "\u001b[31mAda", "Ada\rX", "Ada\tX"})
    void controlCharactersInTheDisplayNameAreRejected(String name) {
        assertThat(invalidFields(new RegisterRequest("ada@example.com", PASSWORD, name))).containsExactly("displayName");
    }

    @Test
    void unicodeDisplayNamesAreAccepted() {
        assertThat(VALIDATOR.validate(new RegisterRequest("ada@example.com", PASSWORD, "Ada Lövelace ☃"))).isEmpty();
    }

    // ---- shape of the JSON and of the log line -----------------------------------------------------------

    @Test
    void aRoleCannotBeSuppliedBecauseNoSuchPropertyExists() {
        String json = "{\"email\":\"ada@example.com\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Ada\",\"role\":\"ADMIN\"}";

        assertThatThrownBy(() -> TestJson.applicationMapper().readValue(json, RegisterRequest.class))
                .hasMessageContaining("role");
    }

    @Test
    void toStringNeverContainsThePassword() {
        assertThat(withPassword(PASSWORD).toString()).doesNotContain(PASSWORD).contains("ada@example.com");
    }
}
