package com.techinterviewer.common.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.Section;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImmutablesTest {

    @Test
    void nullBecomesEmpty() {
        assertThat(Immutables.list(null)).isEmpty();
        assertThat(Immutables.map(null)).isEmpty();
        assertThat(Immutables.enumMap(null, Section.class)).isEmpty();
        assertThat(Immutables.enumSet(null, Section.class)).isEmpty();
    }

    @Test
    void copiesAreIndependentOfTheSourceAndCannotBeModified() {
        List<String> source = new ArrayList<>(List.of("a"));
        List<String> copy = Immutables.list(source);
        source.add("b");

        assertThat(copy).containsExactly("a");
        assertThatThrownBy(() -> copy.add("c")).isInstanceOf(UnsupportedOperationException.class);

        Map<String, Integer> sourceMap = new LinkedHashMap<>(Map.of("a", 1));
        Map<String, Integer> mapCopy = Immutables.map(sourceMap);
        sourceMap.put("b", 2);
        assertThat(mapCopy).containsOnlyKeys("a");
        assertThatThrownBy(() -> mapCopy.put("c", 3)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapKeepsTheIterationOrderOfItsSource() {
        Map<String, Integer> source = new LinkedHashMap<>();
        for (String key : List.of("z", "m", "a", "q", "b")) {
            source.put(key, 1);
        }

        assertThat(Immutables.map(source).keySet()).containsExactly("z", "m", "a", "q", "b");
    }

    @Test
    void enumCollectionsFollowTheDeclarationOrder() {
        Map<Section, Integer> source = new LinkedHashMap<>();
        source.put(Section.SYSTEM_DESIGN, 1);
        source.put(Section.INTRODUCTION, 2);

        assertThat(Immutables.enumMap(source, Section.class).keySet()).containsExactly(Section.INTRODUCTION, Section.SYSTEM_DESIGN);
        assertThat(Immutables.enumSet(List.of(Section.CODING, Section.INTRODUCTION), Section.class))
                .containsExactly(Section.INTRODUCTION, Section.CODING);
        assertThatThrownBy(() -> Immutables.enumSet(Set.of(Section.CODING), Section.class).add(Section.INTRODUCTION))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullElementsKeysAndValuesAreRejected() {
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        Map<String, Integer> nullValue = new LinkedHashMap<>();
        nullValue.put("a", null);
        Map<String, Integer> nullKey = new LinkedHashMap<>();
        nullKey.put(null, 1);

        assertThatThrownBy(() -> Immutables.list(withNull)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Immutables.map(nullValue)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Immutables.map(nullKey)).isInstanceOf(NullPointerException.class);
    }
}
