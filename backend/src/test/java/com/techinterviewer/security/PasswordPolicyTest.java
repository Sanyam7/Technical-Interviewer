package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void limitsAreTheOnesOfTheApiContract() {
        assertThat(PasswordPolicy.MIN_BYTES).isEqualTo(10);
        assertThat(PasswordPolicy.MAX_BYTES).isEqualTo(72);
    }

    @Test
    void lengthIsCountedInUtf8BytesNotCharacters() {
        assertThat(PasswordPolicy.byteLength("")).isZero();
        assertThat(PasswordPolicy.byteLength("abc")).isEqualTo(3);
        assertThat(PasswordPolicy.byteLength("é")).as("e acute: two bytes").isEqualTo(2);
        assertThat(PasswordPolicy.byteLength("€")).as("euro sign: three bytes").isEqualTo(3);
        assertThat(PasswordPolicy.byteLength("🔑")).as("key emoji: four bytes").isEqualTo(4);
    }
    @Test
    void theMostCommonPasswordsAreRefusedWhateverTheirCaseAndPunctuation() {
        for (String common : new String[] {"password123", "Password-123!", "1234567890", "QWERTY-uiop", "iloveyou123", "Passw0rd123",
            "letmein 123", "１２３４５６７８９０"}) {
            assertThat(PasswordPolicy.weakness(common, "ada@example.com", "Ada")).as(common).isPresent();
        }
        assertThat(PasswordPolicy.weakness("password123", "ada@example.com", "Ada").get()).doesNotContain("password123");
    }

    @Test
    void aPasswordMadeOfOneOrTwoCharactersOrOfThePersonsOwnNameIsRefused() {
        assertThat(PasswordPolicy.weakness("aaaaaaaaaaaa", "ada@example.com", "Ada")).hasValueSatisfying(reason -> assertThat(reason).contains("repeats"));
        assertThat(PasswordPolicy.weakness("abababababab", "ada@example.com", "Ada")).isPresent();
        assertThat(PasswordPolicy.weakness("lovelace-2026!", "lovelace@example.com", "Ada Lovelace")).hasValueSatisfying(reason -> assertThat(reason).contains("e-mail"));
        assertThat(PasswordPolicy.weakness("my-ada-lovelace-pw", "x@example.com", "Ada Lovelace")).hasValueSatisfying(reason -> assertThat(reason).contains("name"));
    }

    @Test
    void anOrdinaryStrongPasswordAndAPassphraseAreAccepted() {
        assertThat(PasswordPolicy.weakness("correct-horse-battery", "ada@example.com", "Ada")).isEmpty();
        assertThat(PasswordPolicy.weakness("k7#Rw!q2Zp9x", "ada@example.com", "Ada")).isEmpty();
        assertThat(PasswordPolicy.weakness("a short name ada is fine in a longer passphrase", "ada@example.com", "Ada"))
                .as("a name shorter than four characters does not count").isEmpty();
    }
}
