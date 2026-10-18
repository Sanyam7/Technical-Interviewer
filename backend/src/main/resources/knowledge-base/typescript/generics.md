---
title: TypeScript generics
technology: typescript
skill: typescript
topic: generics
doc_type: REFERENCE
---

## Generic functions and types

Generics write functions and types that work for many types while preserving the relationship between inputs and
outputs: `function first<T>(items: T[]): T | undefined` returns the element type of its argument, which `any` would
lose. Generic types such as `Promise<T>`, `Array<T>` and `Record<K, V>` are parameterised the same way. Type arguments
are usually inferred from the call.

## Constraints

`<T extends { id: string }>` constrains T so the function can use `item.id`. `<K extends keyof T>` restricts a key to
the keys of T, enabling a typed `getProperty(obj, key)` that returns `T[K]`, the indexed access type. Constraints let
generic code use members while keeping the caller's specific type.

## Defaults and inference

Type parameters can have defaults (`<T = string>`). Inference picks the most specific type it can from the arguments;
a `const` type parameter (`<const T>`) infers literal types. When inference produces an unwanted wide type, explicit
type arguments or overloads help.

## Generics versus unions and overloads

Use a generic when the output type depends on the input type. If a function accepts several types but its result
does not depend on which, a union parameter is simpler. Overloads describe a finite set of input-output pairs.
Unnecessary type parameters that appear only once add complexity without adding safety.

## Variance

Function parameter types are checked contravariantly under `strictFunctionTypes` (for function type syntax), while
arrays are covariant and therefore unsound for writes: a `Dog[]` can be passed as `Animal[]` and a `Cat` pushed into
it. Explicit variance annotations (`in` and `out` on type parameters) document and check variance.
