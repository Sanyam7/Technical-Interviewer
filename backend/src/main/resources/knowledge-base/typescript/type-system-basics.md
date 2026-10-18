---
title: TypeScript type system basics
technology: typescript
skill: typescript
topic: type-system-basics
doc_type: REFERENCE
---

## Structural typing

TypeScript types are structural: a value is compatible with a type if it has the required members, whatever it was
declared as, unlike nominal typing in Java. Object literals assigned directly get excess property checks, which catch
misspelled fields. Types exist only at compile time and are erased in the emitted JavaScript, so they cannot be
inspected at runtime; data from outside (an API response) must be validated at runtime, for example with a schema
library.

## Interfaces and type aliases

An interface describes an object shape and can be extended and merged by redeclaration (declaration merging). A type
alias can name any type, including unions, intersections, tuples, primitives and mapped or conditional types. For
object shapes both work; interfaces give clearer error messages and are extendable by consumers, while unions and
computed types need aliases.

## Unions, intersections and literal types

A union `A | B` is a value of either type, and only members common to all members of the union can be used until the
value is narrowed. An intersection `A & B` has the members of both. Literal types restrict a value to exact values
(`'GET' | 'POST'`), which with unions model finite states better than plain strings. `as const` infers the narrowest
literal and readonly types.

## any, unknown and never

`any` switches type checking off for a value and spreads silently. `unknown` is the type-safe top type: anything can
be assigned to it, but it must be narrowed before use, which makes it right for untrusted input. `never` is the type
with no values, the result of functions that never return and of exhausted unions. `void` marks functions whose return
value should not be used.

## Inference

TypeScript infers types from initialisers, return statements and context (the parameters of a callback passed to
`map`), so annotations are needed mainly on function parameters and public APIs. Explicit return types on exported
functions document intent and stop accidental changes.
