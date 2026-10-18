package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void aGeneratedValueIs256BitsOfBase64UrlWithoutPadding() {
        String value = OpaqueTokens.generate(random);

        assertThat(value).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(value)).hasSize(32);
    }

    @Test
    void generatedValuesDoNotRepeat() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            values.add(OpaqueTokens.generate(random));
        }

        assertThat(values).hasSize(1000);
    }

    @Test
    void theValueIsTakenFromTheGivenRandomSource() {
        SecureRandom fixed = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) 0xFF);
            }
        };

        assertThat(OpaqueTokens.generate(fixed)).isEqualTo("_".repeat(42) + "8");
    }

    @Test
    void theStoredFormIsTheLowerCaseHexSha256() {
        assertThat(OpaqueTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(OpaqueTokens.hash("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void hashingIsDeterministicAndDistinguishesValues() {
        assertThat(OpaqueTokens.hash("a")).isEqualTo(OpaqueTokens.hash("a")).isNotEqualTo(OpaqueTokens.hash("b"));
    }
}
