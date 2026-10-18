---
title: React rendering and reconciliation
technology: react
skill: react
topic: rendering-reconciliation
doc_type: REFERENCE
---

## Render and commit phases

Rendering means React calls components to compute what the UI should look like; it is pure and can be interrupted,
repeated or discarded. In the commit phase React applies the minimal changes to the DOM, then runs layout effects and
later passive effects. A component re-renders when its state changes, when its parent re-renders (by default, even
with equal props) or when a context it uses changes. A re-render does not necessarily change the DOM.

## Reconciliation and the diffing heuristics

React compares the new element tree with the previous one. Elements of a different type at the same position cause
the old subtree to be unmounted and a new one mounted, losing its state; the same type keeps the instance and updates
its props. This heuristic makes diffing O(n) instead of the cost of a general tree diff. Defining a component inside
another component creates a new type on every render, which remounts it and loses its state each time.

## Keys

Keys identify list items across renders so React can match, move, insert and delete items correctly. Keys must be
stable and unique among siblings, usually an id from the data. Using the array index as a key breaks when items are
reordered, inserted or removed: state and uncontrolled input values stay attached to positions instead of items.
Changing a component's key deliberately resets its state.

## Batching and concurrent rendering

React batches several state updates in the same event (and, since React 18, in timeouts and promises) into one
re-render. Concurrent rendering lets React prepare a new UI in the background and interrupt it for more urgent
updates: `startTransition` marks an update as non-urgent so typing stays responsive while a large list re-renders, and
`useDeferredValue` defers a value. Suspense shows a fallback while a part of the tree waits for code or data.

## The virtual DOM

The virtual DOM is the in-memory tree of elements React diffs against. Its benefit is a declarative programming model
with reasonably efficient updates, not that it is always faster than direct DOM manipulation.
