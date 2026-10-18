---
title: Node.js runtime
technology: nodejs
doc_type: REFERENCE
---

## Architecture and the event loop

Node.js runs JavaScript on the V8 engine with an event loop provided by libuv. JavaScript code runs on one main
thread; network I/O is non-blocking at the operating-system level, while file system operations, DNS lookups, some
crypto and compression run on libuv's thread pool (four threads by default, `UV_THREADPOOL_SIZE`). This makes Node.js
efficient for many concurrent I/O-bound connections, and poor at CPU-bound work on the main thread, which blocks every
request.

## Event loop phases

Each loop iteration runs phases in order: timers (expired `setTimeout` and `setInterval` callbacks), pending
callbacks, poll (retrieve new I/O events and run their callbacks, waiting here when idle), check (`setImmediate`
callbacks) and close callbacks. `process.nextTick` callbacks run before promise microtasks, and both run after the
current operation before the loop continues, so recursive `nextTick` calls can starve I/O. Inside an I/O callback,
`setImmediate` always runs before a zero-delay `setTimeout`.

## CPU-bound work: worker threads and clustering

Worker threads run JavaScript in parallel threads with their own event loops and communicate by messages or shared
memory, suitable for CPU-heavy tasks such as image processing or hashing. The cluster module, or simply several
processes behind a load balancer or a process manager, runs one process per core to use all CPUs for a server.
Synchronous APIs (`fs.readFileSync`, heavy JSON parsing of huge payloads) should be avoided in request handlers.

## Streams and back-pressure

Streams process data piece by piece instead of loading it all into memory: readable, writable, duplex and transform
streams. Back-pressure means a fast producer must pause when the consumer's buffer is full; `write` returning false
signals it, and `stream.pipeline` connects streams with back-pressure and error propagation and cleans up on failure.

## Modules and error handling

Node.js supports CommonJS (`require`, synchronous loading) and ES modules (`import`, asynchronous, the standard),
selected by the file extension or the `type` field in package.json. Errors in callbacks follow the error-first
convention; promise-based APIs reject. An uncaught exception or unhandled rejection crashes the process by default,
which is safer than continuing in an unknown state; a process manager restarts it, and graceful shutdown closes the
server and finishes in-flight requests on SIGTERM.
