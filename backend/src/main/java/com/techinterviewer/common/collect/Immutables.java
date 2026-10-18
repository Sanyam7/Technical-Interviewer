package com.techinterviewer.common.collect;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Defensive copies for the collections held by value records. A missing collection becomes an empty one
 * (so absent JSON properties are harmless), null elements are rejected, and the result cannot be modified
 * through the argument or through the record.
 */
public final class Immutables {

    private Immutables() {
    }

    public static <T> List<T> list(@Nullable Collection<? extends T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    /** Keeps the iteration order of the source, which {@link Map#copyOf} does not. */
    public static <K, V> Map<K, V> map(@Nullable Map<? extends K, ? extends V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<K, V> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "map key"), Objects.requireNonNull(value, "map value")));
        return Collections.unmodifiableMap(copy);
    }

    /** Ordered by the declaration order of the enum, so serialisation is deterministic. */
    public static <K extends Enum<K>, V> Map<K, V> enumMap(@Nullable Map<K, ? extends V> source, Class<K> keyType) {
        EnumMap<K, V> copy = new EnumMap<>(keyType);
        if (source != null) {
            source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "map key"), Objects.requireNonNull(value, "map value")));
        }
        return Collections.unmodifiableMap(copy);
    }

    public static <E extends Enum<E>> Set<E> enumSet(@Nullable Collection<E> source, Class<E> type) {
        EnumSet<E> copy = EnumSet.noneOf(type);
        if (source != null) {
            source.forEach(element -> copy.add(Objects.requireNonNull(element, "set element")));
        }
        return Collections.unmodifiableSet(copy);
    }
}
