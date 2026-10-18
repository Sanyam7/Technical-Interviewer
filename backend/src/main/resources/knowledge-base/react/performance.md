---
title: React performance
technology: react
skill: react
topic: performance
doc_type: REFERENCE
---

## Measure first

Use the React DevTools Profiler to see which components render, how often and how long, and the browser performance
panel for long tasks, layout and paint. Optimise components that render often and slowly; most re-renders are cheap.
Production builds are much faster than development builds, so measure in production mode.

## Avoiding unnecessary re-renders

`React.memo` skips re-rendering a component when its props are shallowly equal to the previous ones; it only works if
the parent passes stable references, which `useMemo` and `useCallback` provide for objects and functions. Keeping state
as close as possible to where it is used (colocation) limits how much of the tree re-renders. Splitting a large
context, or separating a context's value from its setter, stops every consumer from re-rendering when any part
changes. Passing `children` from a parent avoids re-rendering them when an intermediate wrapper's state changes.

## Large lists

Rendering thousands of rows creates thousands of DOM nodes. List virtualization (windowing, with libraries such as
TanStack Virtual or react-window) renders only the visible rows plus a small buffer, keeping DOM size and render time
constant. Pagination or infinite loading reduces the data rendered at once.

## Code splitting and bundle size

`React.lazy` with dynamic `import()` and Suspense splits code so routes or heavy components load only when needed,
reducing the initial bundle and time to interactive. Analyse the bundle to find large dependencies, prefer
tree-shakeable imports, and lazy-load rarely used features.

## Expensive work and responsiveness

Expensive calculations can be memoised with `useMemo`, moved to a web worker, or deferred with `useDeferredValue` and
`startTransition` so urgent updates such as typing are not blocked. Debouncing input-driven requests reduces work and
network traffic.
