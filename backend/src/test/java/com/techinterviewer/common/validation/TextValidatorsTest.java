package com.techinterviewer.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;

class TextValidatorsTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private record OneLine(@SingleLineText @Nullable String value) {
    }

    private record ManyLines(@MultilineText @Nullable String value) {
    }

    private static boolean oneLine(String value) {
        return VALIDATOR.validate(new OneLine(value)).isEmpty();
    }

    private static boolean manyLines(String value) {
        return VALIDATOR.validate(new ManyLines(value)).isEmpty();
    }

    @Test
    void nullIsLeftToOtherConstraints() {
        assertThat(oneLine(null)).isTrue();
        assertThat(manyLines(null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ada Lovelace", "Zoë Müller", "山田 太郎", "Ana 👩‍💻", "O'Brien-Smith", "100% effort", ""})
    void ordinaryNamesPassOnOneLine(String value) {
        assertThat(oneLine(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\tb", "a\nb", "a\rb", "a\u0085b", "a\u007Fb", "a b", "a b",
        "a‮b", "a⁦b", "a‏b", "a\uD800b"})
    void controlSeparatorBidiAndBrokenSurrogateCharactersAreRefusedOnOneLine(String value) {
        assertThat(oneLine(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"line one\nline two", "tab\tseparated", "windows\r\nlines", "public static void main() {\n\treturn;\n}",
        "emoji 👩‍💻 and 世界"})
    void multilineTextMayContainTabsAndLineBreaks(String value) {
        assertThat(manyLines(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\u0001b", "a\u001Bb", "a\u007Fb", "a\u0085b", "a‮b", "a⁩b", "a\uDC00b"})
    void multilineTextStillRefusesOtherControlCharactersBidiAndBrokenSurrogates(String value) {
        assertThat(manyLines(value)).isFalse();
    }
}
