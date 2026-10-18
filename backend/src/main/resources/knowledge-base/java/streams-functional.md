---
title: Java streams and functional programming
technology: java
skill: java
topic: streams-functional
doc_type: REFERENCE
---

## Lambdas and functional interfaces

A lambda is an implementation of a functional interface, an interface with exactly one abstract method such as
`Function<T,R>`, `Predicate<T>`, `Supplier<T>`, `Consumer<T>` or `Runnable`. Lambdas can capture local variables
only if they are effectively final, because the lambda may run after the method returns and captures a copy of the
value. Method references (`String::length`, `this::process`) are shorthand for lambdas that call one method.

## Stream pipelines and laziness

A stream pipeline has a source, zero or more intermediate operations (`filter`, `map`, `flatMap`, `sorted`,
`distinct`) and one terminal operation (`collect`, `forEach`, `reduce`, `count`, `findFirst`). Intermediate operations
are lazy: nothing runs until the terminal operation, and elements flow through the pipeline one at a time, so
short-circuiting operations such as `findFirst`, `anyMatch` and `limit` can stop early, even on infinite streams. A
stream can be consumed only once. Stateful operations such as `sorted` and `distinct` must see all elements before
emitting. Streams should not mutate external state from lambdas: side effects make parallel streams incorrect.

## Collectors

`Collectors.toList`, `toSet` and `toMap` gather results; `toMap` throws `IllegalStateException` on duplicate keys
unless a merge function is given. `groupingBy` builds a map of lists (or of downstream results such as `counting`),
and `partitioningBy` splits by a predicate into true and false groups. `Stream.toList()` (Java 16) returns an
unmodifiable list. `joining` concatenates strings.

## Optional

`Optional` is a return type for "maybe no result"; it forces callers to handle absence. Use `map`, `filter`,
`orElse`, `orElseGet` (lazy default) and `orElseThrow`, not `get` without checking. `orElse` evaluates its argument
even when a value is present, which matters if the default is expensive. Do not use `Optional` for fields, method
parameters or collections.

## Parallel streams

`parallelStream` splits work across the common ForkJoinPool. It helps only for large, CPU-bound workloads with cheap
splitting (arrays, `ArrayList`) and no shared mutable state; for small collections, I/O-bound work or `LinkedList`
sources it is often slower. Blocking tasks in parallel streams starve the shared common pool.
