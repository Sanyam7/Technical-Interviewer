---
title: React hooks
technology: react
skill: react
topic: hooks
doc_type: REFERENCE
---

## Rules of hooks

Hooks are called only at the top level of a function component or a custom hook, never inside conditions, loops or
nested functions, and only from React functions. React identifies each hook by its call order during render, so a
conditional hook call shifts the order and attaches state to the wrong hook. The ESLint plugin for hooks enforces these
rules and checks effect dependencies.

## useState and useReducer

`useState` returns the current value and a setter; the initial value is used only on the first render, and a lazy
initialiser (`useState(() => expensive())`) avoids recomputing it. `useReducer` centralises complex state transitions
in a pure reducer function `(state, action) => newState`, which suits state with several related fields or transitions
that depend on the previous state.

## useRef

`useRef` returns a mutable object whose `current` property persists across renders without causing a re-render when
changed. It holds DOM nodes (passed through the `ref` attribute) and instance-like values such as timer ids or the
latest callback. Reading or writing refs during rendering makes output unpredictable, so use them in effects and event
handlers.

## useMemo and useCallback

`useMemo` caches the result of a computation between renders until its dependencies change; `useCallback` caches a
function identity. They matter when a value is expensive to compute, or when a stable reference prevents re-rendering
a memoised child (`React.memo`) or re-running an effect. They add overhead and complexity, so use them where profiling
or reference stability requires it; the React Compiler can add such memoisation automatically.

## Custom hooks and stale closures

A custom hook is a function whose name starts with `use` and that calls other hooks, extracting reusable stateful
logic (for example `useDebounce` or `useFetch`); each component that calls it gets its own state. Functions defined
during a render capture the props and state of that render; an effect or interval created once and never updated keeps
reading old values (a stale closure). Listing every used value in the dependency array, using the updater form of
setters, or a ref holding the latest value fixes it.
