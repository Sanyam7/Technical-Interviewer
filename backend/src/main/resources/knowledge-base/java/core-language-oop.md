---
title: Java core language and object-oriented design
technology: java
skill: java
topic: core-language-oop
doc_type: REFERENCE
---

## Encapsulation, inheritance and polymorphism

Encapsulation hides an object's state behind methods so the class can enforce its invariants; fields are private and
change only through operations that keep the object valid. Inheritance (`extends`) lets a subclass reuse and
specialise a superclass, but it couples the subclass to the superclass's implementation details, which is why
"favour composition over inheritance" is common advice. Polymorphism means a call through a supertype reference runs
the implementation of the object's runtime class: instance methods are dispatched dynamically, while static methods,
fields and private methods are resolved at compile time and are not overridden.

## Interfaces and abstract classes

An interface declares a contract; since Java 8 it may contain default and static methods, and since Java 9 private
methods, but it has no instance state. An abstract class can hold state, constructors and protected members. A class
extends at most one class but implements any number of interfaces. Prefer an interface for a capability shared by
unrelated types and an abstract class for a family that shares implementation. If two interfaces provide the same
default method, the implementing class must override it to resolve the conflict.

## equals and hashCode

`equals` must be reflexive, symmetric, transitive, consistent and return false for null. Whenever `equals` is
overridden, `hashCode` must be overridden too, so that equal objects have equal hash codes; otherwise hash-based
collections such as `HashMap` and `HashSet` fail to find elements that are equal. Fields used in `equals` and
`hashCode` should not change while the object is a key in a hash collection, because the entry would then sit in the
wrong bucket. Using `instanceof` in `equals` can break symmetry between a class and a subclass that adds state.

## Immutability, records and enums

An immutable class has final fields set in the constructor, no setters, defensive copies of mutable inputs and
outputs, and is usually final so subclasses cannot add mutability. Immutable objects are thread-safe without
synchronisation and are safe hash keys. A `record` (Java 16) is a transparent carrier of immutable data: the compiler
generates the canonical constructor, accessors, `equals`, `hashCode` and `toString`, and a compact constructor can
validate arguments. Record fields are shallowly immutable: a record holding a `List` must copy it to be truly
immutable. Enums are singleton-per-constant classes that can have fields, constructors and methods, and an enum is the
simplest correct way to implement a singleton.

## Pass-by-value and object identity

Java is always pass-by-value: for objects the value passed is a copy of the reference, so a method can mutate the
object the caller sees but cannot make the caller's variable point to another object. `==` compares references for
objects and values for primitives; `equals` compares logical equality. String literals are interned, so `==` may
appear to work on strings by accident, but string comparison must use `equals`.
