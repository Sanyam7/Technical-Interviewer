---
title: TypeScript narrowing and type guards
technology: typescript
skill: typescript
topic: narrowing-type-guards
doc_type: REFERENCE
---

## Control-flow narrowing

TypeScript narrows a union type inside branches based on checks it understands: `typeof x === 'string'` narrows to
primitives, `x instanceof Date` to a class, `'swim' in animal` to types with that property, equality checks to literal
types, and truthiness checks remove null and undefined (but also discard 0 and empty strings). Narrowing follows
assignments and early returns. Note that `typeof null` is `'object'`.

## Discriminated unions

A discriminated (tagged) union gives every member a common literal property, such as `kind: 'circle' | 'square'` or
`status: 'loading' | 'success' | 'error'`. Switching on the tag narrows to the matching member, so only the fields that
exist in that state are accessible. This models state machines safely, for example a request that has data only in the
success state, and avoids optional fields that can be inconsistent.

## User-defined type guards and assertion functions

A function returning a type predicate `value is Fish` tells the compiler that a true result narrows the argument;
the compiler trusts it, so a wrong predicate is a lie the checker cannot catch. An assertion function with `asserts
value is T` narrows after the call returns and throws otherwise. Type guards are the bridge between runtime validation
of `unknown` input and static types.

## Exhaustiveness checking

After handling every member of a union in a switch, the remaining type is `never`. Assigning the value to a variable
of type `never` in the default branch (`const unreachable: never = shape`) makes the compiler report an error when a
new union member is added and not handled, turning a forgotten case into a compile-time error.

## Type assertions

`value as Type` tells the compiler to treat a value as a type without any runtime check; it is unsafe and should be
rare. The non-null assertion `x!` removes null and undefined the same way. Prefer narrowing and validation.
