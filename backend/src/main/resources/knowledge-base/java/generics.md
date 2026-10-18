---
title: Java generics
technology: java
skill: java
topic: generics
doc_type: REFERENCE
---

## Type parameters and bounded types

Generics let classes and methods be written once for many types while keeping compile-time type safety, removing
casts. A bounded type parameter `<T extends Comparable<T>>` restricts T to types that implement the bound, so the
method can call `compareTo`. Multiple bounds are written `<T extends Number & Comparable<T>>`, with the class first.

## Type erasure

Java generics are implemented by erasure: the compiler checks types and then replaces type parameters with their
bounds (or `Object`) and inserts casts. At runtime a `List<String>` and a `List<Integer>` have the same class, so you
cannot write `new T()`, create a generic array `new T[]`, use `instanceof List<String>`, or overload methods whose
signatures differ only in type arguments. Erasure was chosen for backward compatibility with pre-generics code. The
compiler may generate bridge methods so that overriding still works after erasure.

## Wildcards and PECS

Generic types are invariant: a `List<Integer>` is not a `List<Number>`, because otherwise you could add a `Double`
to a list of integers. Wildcards add flexibility. `List<? extends Number>` is a producer: you can read `Number`s from
it but cannot add anything except null. `List<? super Integer>` is a consumer: you can add `Integer`s but reads give
only `Object`. The rule "producer extends, consumer super" (PECS) explains signatures like
`Collections.copy(List<? super T> dest, List<? extends T> src)`. Unbounded `List<?>` is for code that does not care
about the element type.

## Raw types and heap pollution

A raw type (`List` without type arguments) disables generic checks and exists only for legacy code; mixing raw and
generic types produces unchecked warnings and can cause a `ClassCastException` far from the cause, which is called
heap pollution. Varargs of a generic type (`T...`) create a generic array and can pollute the heap; `@SafeVarargs`
documents that a method does not do anything unsafe with it.
