---
title: React state management
technology: react
skill: react
topic: state-management
doc_type: REFERENCE
---

## Kinds of state

Local UI state (an open dropdown, form input) belongs in the component with `useState`. Shared client state (the
current user, a theme, a shopping cart) is needed by distant components. Server state (data fetched from an API) is a
cache of remote data with its own concerns: loading, staleness, refetching and invalidation. URL state (filters,
pagination) belongs in the URL so it survives reloads and can be shared. Mixing these kinds in one global store is a
common source of complexity.

## Context

Context passes a value through the tree without prop drilling: a provider supplies it and any descendant reads it
with `useContext`. Every consumer re-renders when the provider's value changes, so a value object created on every
render re-renders all consumers; memoise it or split contexts. Context is a dependency-injection mechanism suited to
values that change rarely (theme, current user, services), not a high-frequency state store.

## Reducers

`useReducer` with a reducer function keeps state transitions explicit and testable; combined with context it gives a
small Redux-like store. Actions describe what happened, and the reducer computes the next state immutably.

## External stores

Libraries such as Redux Toolkit, Zustand, Jotai or MobX hold shared client state outside React with selective
subscriptions, so components re-render only when the slice they select changes. `useSyncExternalStore` is the hook that
connects external stores to concurrent rendering safely. Use one when shared state is large or updates frequently;
small apps rarely need one.

## Derived state

Values that can be computed from existing state or props should be computed during render, not stored and
synchronised with effects; duplicated state drifts out of sync. Server data should come from a server-state library or
loader rather than being copied into a global store.
