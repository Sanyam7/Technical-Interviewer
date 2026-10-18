---
title: JavaScript language core
technology: javascript
skill: javascript
topic: language-core
doc_type: REFERENCE
---

## Scope, hoisting and closures

`var` is function-scoped and hoisted with the value undefined; `let` and `const` are block-scoped and sit in the
temporal dead zone until their declaration runs, so reading them earlier throws. `const` prevents reassignment, not
mutation of the object. A closure is a function together with the variables of the scope where it was created; it
keeps them alive after the outer function returns, which enables private state, factories and callbacks. The classic
bug of `var` in a loop creating callbacks that all see the last value disappears with `let`, which creates a new
binding per iteration.

## this binding

`this` is decided by how a function is called: as a method (`obj.f()`) it is the object; as a plain function it is
undefined in strict mode (the global object otherwise); with `new` it is the new object; with `call`, `apply` or
`bind` it is the given value. Arrow functions have no own `this` and take it from the enclosing scope, which makes them
convenient for callbacks inside methods but wrong as object methods that need their receiver. Passing a method as a
callback (`setTimeout(obj.f)`) loses its `this`.

## Prototypes and classes

Objects inherit through a prototype chain: a property lookup that fails on the object continues on its prototype.
`class` syntax is mostly sugar over constructor functions and prototypes, adding private fields (`#field`), static
blocks and stricter semantics. Methods defined in a class live on the prototype and are shared by instances.

## Equality and type coercion

`===` compares without type conversion; `==` converts types with surprising rules (`'' == 0` and `null == undefined`
are true), so `===` is the default. `NaN` is not equal to itself; use `Number.isNaN`. `Object.is` treats `NaN` as equal
to itself and distinguishes `+0` from `-0`. Falsy values are false, 0, -0, 0n, '', null, undefined and NaN; everything
else, including '0' and empty arrays, is truthy. The nullish coalescing operator `??` falls back only for null and
undefined, unlike `||`.

## Values and references

Primitives (string, number, bigint, boolean, undefined, null, symbol) are immutable values; objects, arrays and
functions are referenced. Copying an object with spread (`{...obj}`) is shallow: nested objects are shared;
`structuredClone` makes a deep copy. Numbers are IEEE 754 doubles, so `0.1 + 0.2 !== 0.3` and integers are exact only up
to `Number.MAX_SAFE_INTEGER`; use bigint for larger integers.
