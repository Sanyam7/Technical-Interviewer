package com.techinterviewer.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {

    private static final Pattern ADDRESS = Pattern.compile(EmailAddress.REGEX);

    @Test
    void normalizeTrimsAndLowerCases() {
        assertThat(EmailAddress.normalize("  Ada.Lovelace@Example.COM \n")).isEqualTo("ada.lovelace@example.com");
    }

    @Test
    void normalizeIsIdempotent() {
        String once = EmailAddress.normalize(" MiXeD@Example.com ");

        assertThat(EmailAddress.normalize(once)).isEqualTo(once);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ada@example.com", "a@b.co", "first.last+tag@sub.example.org", "o'brien@example.com",
        "under_score@example.com", "x@a-b.example.com", "A@EXAMPLE.COM", "1@2.3"})
    void acceptsOrdinaryAddresses(String address) {
        assertThat(ADDRESS.matcher(address).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "ada", "ada@", "@example.com", "ada@example", "ada@@example.com", "ada@exa mple.com", "ada example@x.com",
        "ada@-example.com", "ada@example-.com", "ada@example..com", "ada@.example.com", "ada@example.com.",
        "ada@exam_ple.com", "ada@example.com\n", "éa@example.com", "ada@éxample.com", "ada,x@example.com",
        "ada(comment)@example.com", "<ada@example.com>"})
    void rejectsMalformedAddresses(String address) {
        assertThat(ADDRESS.matcher(address).matches()).isFalse();
    }

    @Test
    void localPartIsLimitedTo64Characters() {
        assertThat(ADDRESS.matcher("a".repeat(64) + "@example.com").matches()).isTrue();
        assertThat(ADDRESS.matcher("a".repeat(65) + "@example.com").matches()).isFalse();
    }

    @Test
    void domainLabelsAreLimitedTo63Characters() {
        assertThat(ADDRESS.matcher("a@" + "b".repeat(63) + ".com").matches()).isTrue();
        assertThat(ADDRESS.matcher("a@" + "b".repeat(64) + ".com").matches()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ada@example.com", "a.b+c@sub.example.co", "x@y.zz"})
    void wellFormedAddressesAreRecognised(String email) {
        assertThat(EmailAddress.isWellFormed(email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-at-sign", "a@b", "a\u0000b@example.com", "a b@example.com", "a@exa mple.com", "ünï@example.com"})
    void malformedAddressesAreNotWellFormed(String email) {
        assertThat(EmailAddress.isWellFormed(email)).isFalse();
    }

    @Test
    void anAddressLongerThanTheLimitIsNotWellFormed() {
        String tooLong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".com";

        assertThat(tooLong.length()).isGreaterThan(EmailAddress.MAX_LENGTH);
        assertThat(EmailAddress.isWellFormed(tooLong)).isFalse();
    }
}
