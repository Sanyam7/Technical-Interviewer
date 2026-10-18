---
title: React components, props and state
technology: react
skill: react
topic: components-props-state
doc_type: REFERENCE
---

## Components and JSX

A React component is a function that returns a description of UI (JSX elements) from its props and state. JSX compiles
to function calls that create element objects; it is not HTML, so attributes are camelCase (`className`, `onClick`)
and expressions go in braces. Components should be pure during rendering: the same props and state give the same
output, without side effects such as network calls or mutating variables outside the component. Composition (passing
components and `children`) is preferred over inheritance for reuse.

## Props

Props are the inputs a parent passes to a child; they are read-only, and a component must never modify its props.
Data flows down from parents to children (one-way data flow); children communicate up by calling callback props the
parent passes, such as `onChange`. Passing props through many intermediate layers that do not use them is prop
drilling, which context or composition can avoid.

## State

State is data a component owns that changes over time, declared with `useState`. Setting state schedules a re-render
with the new value; state must be updated immutably, by creating new objects and arrays (`setItems([...items,
item])`) rather than mutating them, because React compares references to detect changes. When the new state depends
on the previous one, use the updater form `setCount(c => c + 1)`, because state updates are batched and the variable
in the closure holds the value of the current render. Derive values from props and state during render instead of
copying them into extra state.

## Lifting state up and controlled inputs

When two components need the same changing data, move the state to their closest common parent and pass it down as
props (lifting state up), keeping a single source of truth. A controlled input gets its value from state and reports
changes through `onChange`, so React state is the source of truth; an uncontrolled input keeps its own value in the
DOM and is read through a ref. Controlled inputs make validation and conditional logic straightforward.

## Conditional rendering and lists

Render conditionally with `&&`, the ternary operator or early returns; note that `{count && <List />}` renders 0 when
count is 0. Lists are rendered with `map`, and every item needs a stable, unique `key` among its siblings.
