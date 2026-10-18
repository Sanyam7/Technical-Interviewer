---
title: Java collections
technology: java
skill: java
topic: collections
doc_type: REFERENCE
---

## List implementations

`ArrayList` is backed by a resizable array: O(1) indexed access, amortised O(1) append, and O(n) insertion or removal
in the middle because elements shift. When full it grows by about 50 percent and copies the array. `LinkedList` is a
doubly linked list: O(1) insertion and removal at a known node or at the ends, but O(n) indexed access and poor cache
locality, so `ArrayList` or `ArrayDeque` is faster in almost every real workload.

## How HashMap works

`HashMap` stores entries in an array of buckets. The key's `hashCode` is spread (its high bits XORed into the low
bits) and masked to pick a bucket; collisions are kept in a linked list that is converted to a balanced red-black tree
when a bucket holds more than eight entries (and the table has at least 64 buckets), bounding the worst case at
O(log n). When the number of entries exceeds capacity times the load factor (0.75 by default) the table doubles and
entries are redistributed. Get and put are O(1) on average. `HashMap` allows one null key, and iteration order is
unspecified; `LinkedHashMap` keeps insertion or access order and can implement an LRU cache by overriding
`removeEldestEntry`. `TreeMap` is a red-black tree with O(log n) operations and sorted keys.

## Sets and queues

`HashSet` is a `HashMap` with dummy values, `LinkedHashSet` keeps insertion order and `TreeSet` keeps keys sorted.
`ArrayDeque` is the preferred stack and queue: it is faster than `Stack` (synchronised, legacy) and `LinkedList`.
`PriorityQueue` is a binary heap: O(log n) offer and poll, O(1) peek, and its iterator does not return elements in
priority order.

## Fail-fast iterators and concurrent collections

The iterators of the standard collections are fail-fast: structurally modifying a collection while iterating over it
(other than through the iterator's own `remove`) throws `ConcurrentModificationException` on a best-effort basis; it
is a bug detector, not a thread-safety guarantee. Use `Iterator.remove` or `removeIf` to delete while iterating.
`ConcurrentHashMap` allows concurrent reads and writes without locking the whole map (it uses CAS and per-bin locks),
never throws `ConcurrentModificationException`, offers atomic compound operations such as `computeIfAbsent` and
`merge`, and does not allow null keys or values. `CopyOnWriteArrayList` copies its array on every write, which suits
lists read far more often than they are changed. `Collections.synchronizedList` wraps every method in one lock but
iteration still needs external synchronisation.

## Unmodifiable versus immutable collections

`List.of`, `Set.of` and `Map.of` return immutable collections that reject nulls and throw
`UnsupportedOperationException` on modification. `Collections.unmodifiableList` returns a read-only view: changes to
the underlying list remain visible through it. `List.copyOf` makes an immutable copy.
