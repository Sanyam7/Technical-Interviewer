---
title: JavaScript asynchrony and the event loop
technology: javascript
skill: javascript
topic: async-event-loop
doc_type: REFERENCE
---

## The event loop

JavaScript runs on a single thread with a call stack. Asynchronous APIs (timers, network, I/O) are handled by the
host environment, which queues callbacks when they complete. The event loop takes a task from the task (macrotask)
queue, runs it to completion, then runs all queued microtasks before the next task or rendering. Long synchronous
work blocks everything: clicks, rendering and other callbacks wait, so heavy computation belongs in a worker.

## Microtasks and macrotasks

Promise reactions (`then`, `catch`, code after `await`) and `queueMicrotask` callbacks are microtasks; `setTimeout`,
`setInterval`, I/O callbacks and UI events are tasks. Microtasks always run before the next task, so
`setTimeout(f, 0)` runs after every pending promise callback. A loop of microtasks that keep scheduling microtasks
starves the task queue and freezes the page.

## Promises

A promise represents a value that will be available later and is pending, fulfilled or rejected. `then` returns a new
promise, so steps chain; returning a promise from a `then` callback waits for it. Errors propagate down the chain to the
next `catch`. `Promise.all` waits for all and rejects as soon as one rejects; `Promise.allSettled` waits for all and
reports each outcome; `Promise.race` settles with the first to settle; `Promise.any` resolves with the first success.
An unhandled rejection is reported by the runtime and in Node.js terminates the process by default.

## async and await

An `async` function always returns a promise; `await` pauses the function until the promise settles, without blocking
the thread, and throws the rejection reason so `try/catch` works. Awaiting inside a loop runs the operations one after
another; to run independent operations concurrently, start them all and `await Promise.all(...)`. Forgetting `await`
produces a promise where a value was expected and loses errors (a floating promise).

## Cancellation and timeouts

Promises cannot be cancelled by themselves. `AbortController` signals cancellation to APIs such as `fetch`, and
`AbortSignal.timeout(ms)` adds a timeout. Cancelling a request does not undo a side effect the server already
performed.
