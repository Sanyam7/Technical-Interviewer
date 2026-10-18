---
title: React effects and data fetching
technology: react
skill: react
topic: effects-data-fetching
doc_type: REFERENCE
---

## What effects are for

`useEffect` synchronises a component with an external system (a network request, a subscription, a timer, the DOM of a
third-party widget) after React commits the render. It is not for transforming data for rendering (compute it during
render) or for reacting to user events (do that in the event handler). The effect runs after the first render and
again whenever a value in its dependency array changes; an empty array runs it once after mount, and omitting the
array runs it after every render.

## Cleanup

An effect can return a cleanup function that React runs before re-running the effect with new dependencies and when
the component unmounts. Cleanups unsubscribe, clear timers, close sockets and abort requests; missing cleanups cause
memory leaks and updates to unmounted components. In development Strict Mode React mounts, unmounts and remounts each
component once to expose missing cleanups, so effects run twice.

## Dependencies

The dependency array must list every reactive value the effect reads (props, state and values derived from them).
Omitting one leads to stale values; silencing the linter hides bugs. Objects and functions created during render are
new on every render, so as dependencies they re-run the effect every time; move them inside the effect, memoise them,
or depend on primitive values instead.

## Race conditions in data fetching

When a fetch depends on a prop such as a user id, a slow response for an old id can arrive after the response for the
new one and overwrite it. Fix it by ignoring stale results with a flag set in the cleanup, or by aborting the previous
request with an `AbortController`. Fetching in effects also creates request waterfalls between parent and child
components.

## Server-state libraries

Server data is a cache of remote state, with loading and error states, deduplication, refetching and invalidation.
Libraries such as TanStack Query or SWR, or framework data loaders and server components, handle caching, retries,
background refresh and race conditions, so hand-written fetching in effects is best kept for simple cases.
