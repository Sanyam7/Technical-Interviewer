---
title: TypeScript strictness and tooling
technology: typescript
skill: typescript
topic: strictness-tooling
doc_type: REFERENCE
---

## The strict flag

`strict: true` enables a family of checks. `strictNullChecks` makes null and undefined separate types, so a value that
can be missing must be checked before use, removing a whole class of runtime errors. `noImplicitAny` reports
parameters and values whose type would silently become `any`. `strictFunctionTypes` checks function parameters
contravariantly, and `strictPropertyInitialization` requires class properties to be assigned in the constructor.
`useUnknownInCatchVariables` types caught errors as `unknown`.

## Extra safety options

`noUncheckedIndexedAccess` adds `undefined` to the type of index access (`array[i]`, record lookups), reflecting that
the element might not exist. `exactOptionalPropertyTypes` distinguishes a missing property from one set to
`undefined`. `noImplicitReturns` and `noFallthroughCasesInSwitch` catch control-flow mistakes.

## tsconfig and module resolution

`tsconfig.json` sets the compiler options and the files of a project. `target` chooses the emitted JavaScript version,
`module` and `moduleResolution` decide how imports are written and resolved (`bundler` for code processed by a
bundler such as Vite, `nodenext` for Node.js ESM with explicit file extensions), and `lib` declares available runtime
APIs. `paths` aliases need matching configuration in the bundler. Project references split large codebases into
separately built projects. With a bundler, TypeScript often only type-checks (`noEmit`) while the bundler strips the
types.

## Declaration files

A `.d.ts` file describes the types of JavaScript code without implementation. Libraries ship their own declarations or
get them from DefinitelyTyped (`@types/...`). `declare module` adds types for untyped modules, and module augmentation
extends existing declarations. `skipLibCheck` skips checking declaration files to speed up builds.

## Linting and type-aware rules

The type checker finds type errors, while a linter (ESLint with typescript-eslint) enforces conventions and catches
patterns the compiler allows, such as floating promises that are never awaited or `any` leaking from untyped
libraries. Type-aware lint rules need type information and are slower but catch more.
