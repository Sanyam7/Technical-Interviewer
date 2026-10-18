---
title: TypeScript utility, mapped and conditional types
technology: typescript
skill: typescript
topic: utility-mapped-conditional-types
doc_type: REFERENCE
---

## Built-in utility types

`Partial<T>` makes every property optional (useful for patch payloads), `Required<T>` makes them required and
`Readonly<T>` read-only. `Pick<T, K>` keeps some properties and `Omit<T, K>` removes them. `Record<K, V>` builds an
object type from a key union. `ReturnType<F>`, `Parameters<F>` and `Awaited<T>` extract types from functions and
promises. `NonNullable<T>`, `Exclude<U, X>` and `Extract<U, X>` filter unions.

## Mapped types

A mapped type builds a new object type by iterating over keys: `{ [K in keyof T]: T[K] | null }`. Modifiers add or
remove `readonly` and `?` (`-readonly`, `-?`). Key remapping with `as` renames or filters keys, for example generating
`getName` accessors with template literal types: `{ [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K] }`.

## Conditional types and infer

A conditional type `T extends U ? X : Y` chooses a type based on assignability. Applied to a union type parameter, it
distributes over each member, which is how `Exclude` works; wrapping both sides in brackets (`[T] extends [U]`) stops
the distribution. `infer` declares a type variable inside the condition to extract part of a type: `type ElementType<T>
= T extends (infer E)[] ? E : never`.

## Template literal types

Template literal types compose string literal types: `` `${Method} /api/${Resource}` `` produces every combination of
two unions. With intrinsic helpers (`Uppercase`, `Capitalize`) they type event names, CSS properties and route
strings.

## Keeping types maintainable

Heavy type-level programming slows the compiler and produces unreadable errors. Prefer simple, named types and use
advanced constructs where they remove real duplication or prevent real bugs, such as deriving request types from a
single schema.
