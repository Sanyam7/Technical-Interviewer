package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestAppProperties;
import com.techinterviewer.config.AppProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class JwtKeyProviderTest {

    private static AppProperties.Jwt jwt(String secret, boolean ephemeral) {
        return TestAppProperties.withJwt(secret, ephemeral, "tech-interviewer", Duration.ofMinutes(15)).security().jwt();
    }

    private static String secretOfLength(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return Base64.getEncoder().encodeToString(random);
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {32, 33, 48, 64, 128})
    void aBase64SecretOfAtLeast32BytesIsUsedAsIs(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);

        JwtKeyProvider provider = JwtKeyProvider.from(jwt(Base64.getEncoder().encodeToString(raw), false));

        assertThat(provider.signingKey().getEncoded()).isEqualTo(raw);
        assertThat(provider.signingKey().getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(provider.isEphemeral()).isFalse();
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, 1, 16, 31})
    void aSecretShorterThan32BytesIsRefusedAndItsLengthReported(int bytes) {
        if (bytes == 0) {
            return; // an empty string is the "not set" case, covered below
        }
        assertThatThrownBy(() -> JwtKeyProvider.from(jwt(secretOfLength(bytes), false)))
                .isInstanceOf(MissingJwtSecretException.class)
                .hasMessageContaining("decodes to " + bytes + " bytes")
                .hasMessageContaining("at least 32");
    }

    @Test
    void surroundingWhitespaceOfTheSecretIsIgnored() {
        String secret = secretOfLength(48);

        assertThat(JwtKeyProvider.from(jwt("  " + secret + "\n", false)).signingKey().getEncoded())
                .isEqualTo(Base64.getDecoder().decode(secret));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "   ", "${JWT_SECRET}", "${JWT_SECRET:}"})
    void anAbsentBlankOrUnresolvedSecretFailsFastWithTheInstructions(String secret) {
        assertThatThrownBy(() -> JwtKeyProvider.from(jwt(secret, false)))
                .isInstanceOf(MissingJwtSecretException.class)
                .hasMessageContaining("JWT_SECRET is not set")
                .hasMessageContaining("openssl rand -base64 48");
    }

    @Test
    void anUnresolvedPlaceholderIsTreatedAsNotSetEvenWhenItWouldAccidentallyBeValidBase64() {
        // "${AAAA...}" is not base64 because of '$', but the rule is about the placeholder, whatever follows it.
        assertThatThrownBy(() -> JwtKeyProvider.from(jwt("${" + "A".repeat(60) + "}", false)))
                .isInstanceOf(MissingJwtSecretException.class)
                .hasMessageContaining("is not set");
        // and an ephemeral key is generated for it when that is allowed, as for any absent secret
        assertThat(JwtKeyProvider.from(jwt("${JWT_SECRET}", true)).isEphemeral()).isTrue();
    }

    @Test
    void aNullSecretFailsFast() {
        assertThatThrownBy(() -> JwtKeyProvider.from(jwt(null, false))).isInstanceOf(MissingJwtSecretException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64 at all!", "%%%%", "abc$def"})
    void aSecretThatIsNotBase64IsRefusedWithoutEchoingIt(String secret) {
        assertThatThrownBy(() -> JwtKeyProvider.from(jwt(secret, false)))
                .isInstanceOf(MissingJwtSecretException.class)
                .hasMessageContaining("not valid base64")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(secret));
    }

    @Test
    void whenAllowedAnAbsentSecretIsReplacedByARandom48ByteKeyAndAWarningIsLogged(CapturedOutput output) {
        JwtKeyProvider first = JwtKeyProvider.from(jwt("", true));
        JwtKeyProvider second = JwtKeyProvider.from(jwt(null, true));

        assertThat(first.isEphemeral()).isTrue();
        assertThat(first.signingKey().getEncoded()).hasSize(48);
        assertThat(second.signingKey().getEncoded()).hasSize(48).isNotEqualTo(first.signingKey().getEncoded());
        assertThat(output.getAll()).contains("WARN").contains("JWT_SECRET is not set").contains("random signing key");
    }

    @Test
    void aConfiguredSecretIsNeverReplacedEvenWhenEphemeralKeysAreAllowed(CapturedOutput output) {
        String secret = secretOfLength(48);

        JwtKeyProvider provider = JwtKeyProvider.from(jwt(secret, true));

        assertThat(provider.isEphemeral()).isFalse();
        assertThat(provider.signingKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(secret));
        assertThat(output.getAll()).doesNotContain("JWT_SECRET is not set");
    }

    @Test
    void anInvalidSecretIsNotReplacedByAnEphemeralOneEvenWhenAllowed() {
        assertThatThrownBy(() -> JwtKeyProvider.from(jwt(secretOfLength(16), true))).isInstanceOf(MissingJwtSecretException.class);
        assertThatThrownBy(() -> JwtKeyProvider.from(jwt("%%%", true))).isInstanceOf(MissingJwtSecretException.class);
    }

    @Test
    void theSecretNeverAppearsInThePropertiesToString() {
        String secret = secretOfLength(48);

        assertThat(jwt(secret, false).toString()).doesNotContain(secret).contains("<redacted>");
        assertThat(jwt("", false).toString()).contains("<unset>");
        assertThat(TestAppProperties.withJwt(secret, false, "x", Duration.ofMinutes(1)).toString()).doesNotContain(secret);
    }

    @Test
    void failureAnalysisNamesTheVariableAndTheCommand() {
        MissingJwtSecretException failure = new MissingJwtSecretException("is not set");

        var analysis = new MissingJwtSecretFailureAnalyzer().analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("JWT_SECRET");
        assertThat(analysis.getAction()).contains("openssl rand -base64 48").contains("JWT_SECRET");
    }

    @Test
    void failureAnalyzerRecognisesTheFailureWrappedInABeanCreationError() {
        Exception wrapped = new org.springframework.beans.factory.BeanCreationException("jwtKeyProvider", "failed",
                new MissingJwtSecretException("is not set"));

        assertThat(new MissingJwtSecretFailureAnalyzer().analyze(wrapped)).isNotNull();
        assertThat(new MissingJwtSecretFailureAnalyzer().analyze(new IllegalStateException("other"))).isNull();
    }

    @Test
    void aPlaceholderSecretWithHardlyAnyVariationIsRefused() {
        String allZeros = Base64.getEncoder().encodeToString(new byte[48]);
        String sevenValues = Base64.getEncoder().encodeToString(java.util.stream.IntStream.range(0, 48)
                .collect(() -> new java.io.ByteArrayOutputStream(), (out, i) -> out.write(i % (JwtKeyProvider.MIN_DISTINCT_BYTE_VALUES - 1)), (a, b) -> {})
                .toByteArray());

        assertThatThrownBy(() -> JwtKeyProvider.from(jwt(allZeros, false)))
                .isInstanceOf(MissingJwtSecretException.class).hasMessageContaining("openssl rand -base64 48");
        assertThatThrownBy(() -> JwtKeyProvider.from(jwt(sevenValues, true))).isInstanceOf(MissingJwtSecretException.class);
    }

    @Test
    void aSecretWithTheMinimumVariationIsAccepted() {
        String eightValues = Base64.getEncoder().encodeToString(java.util.stream.IntStream.range(0, 48)
                .collect(() -> new java.io.ByteArrayOutputStream(), (out, i) -> out.write(i % JwtKeyProvider.MIN_DISTINCT_BYTE_VALUES), (a, b) -> {})
                .toByteArray());

        assertThat(JwtKeyProvider.from(jwt(eightValues, false))).isNotNull();
    }
}
