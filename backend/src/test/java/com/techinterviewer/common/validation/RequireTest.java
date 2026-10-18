package com.techinterviewer.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RequireTest {

    @Test
    void validValuesAreReturnedUnchanged() {
        assertThat(Require.nonNull("x", "f")).isEqualTo("x");
        assertThat(Require.notBlank(" a ", "f")).isEqualTo(" a ");
        assertThat(Require.between(4, 0, 4, "f")).isEqualTo(4);
        assertThat(Require.between(0, 0, 4, "f")).isZero();
        assertThat(Require.between(0.5, 0, 1, "f")).isEqualTo(0.5);
        assertThat(Require.atLeast(3, 3, "f")).isEqualTo(3);
        assertThat(Require.text("", "f", 5)).isEmpty();
        assertThat(Require.nullOrNotBlank(null, "f", 5)).isNull();
    }

    @Test
    void rejectedValuesNameTheField() {
        assertThatThrownBy(() -> Require.nonNull(null, "answer")).hasMessage("answer must not be null");
        assertThatThrownBy(() -> Require.notBlank("  ", "answer")).hasMessage("answer must not be blank");
        assertThatThrownBy(() -> Require.notBlank(null, "answer")).hasMessage("answer must not be blank");
        assertThatThrownBy(() -> Require.between(5, 0, 4, "rating")).hasMessage("rating must be between 0 and 4 but was 5");
        assertThatThrownBy(() -> Require.between(-1, 0, 4, "rating")).hasMessageContaining("rating");
        assertThatThrownBy(() -> Require.atLeast(-1, 0, "count")).hasMessage("count must be at least 0 but was -1");
    }

    @Test
    void lengthLimitIsInclusive() {
        assertThat(Require.notBlank("abc", "f", 3)).isEqualTo("abc");
        assertThatThrownBy(() -> Require.notBlank("abcd", "f", 3)).hasMessage("f must not be longer than 3 characters");
        assertThatThrownBy(() -> Require.text("abcd", "f", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Require.nullOrNotBlank(" ", "f", 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notANumberAndInfinitiesAreNotInAnyRange() {
        assertThatThrownBy(() -> Require.between(Double.NaN, 0, 100, "score")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Require.between(Double.POSITIVE_INFINITY, 0, 100, "score")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Require.between(100.0001, 0, 100, "score")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Require.between(100.0, 0, 100, "score")).isEqualTo(100.0);
    }

    @Test
    void controlCharactersAreRejectedBecauseTheDatabaseCannotStoreThemButWhitespaceIsKept() {
        assertThat(Require.notBlank("line one\nline two\ttabbed\r\n", "f")).contains("\n").contains("\t");
        assertThat(Require.text("a\nb", "f", 5)).isEqualTo("a\nb");
        assertThatThrownBy(() -> Require.notBlank("fine\u0000 text", "summary")).hasMessage("summary must not contain control characters");
        assertThatThrownBy(() -> Require.notBlank("fine\u0000 text", "summary", 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Require.text("\u0000", "rationale", 50)).hasMessage("rationale must not contain control characters");
        assertThatThrownBy(() -> Require.nullOrNotBlank("a\u001Bb", "f", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Require.notBlank("a\u007Fb", "f")).isInstanceOf(IllegalArgumentException.class);
    }
}
