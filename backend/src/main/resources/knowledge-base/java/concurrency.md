---
title: Java concurrency
technology: java
skill: java
topic: concurrency
doc_type: REFERENCE
---

## Race conditions and atomicity

A race condition happens when the result depends on the interleaving of threads. `count++` on a shared field is not
atomic: it is a read, an add and a write, so two threads can read the same value and one update is lost. Making the
field `volatile` does not fix this, because volatile guarantees visibility, not atomicity of a read-modify-write.
Fixes are a lock (`synchronized` or `ReentrantLock`) around the whole operation, or an atomic class such as
`AtomicInteger.incrementAndGet`, which uses compare-and-swap (CAS); under heavy contention `LongAdder` scales better.

## The Java memory model and volatile

The Java memory model defines when a write by one thread is visible to another through the happens-before relation.
Without it, threads may see stale values because of CPU caches and compiler or processor reordering. Releasing a
monitor happens-before every later acquisition of the same monitor; a write to a volatile variable happens-before
every later read of it; `Thread.start` happens-before the actions of the started thread, and a thread's actions
happen-before another thread returns from `join` on it. A volatile field is suitable for a flag written by one thread
and read by others. Double-checked locking is only correct when the instance field is volatile, because otherwise
another thread may see a partially constructed object.

## synchronized, locks and deadlock

`synchronized` acquires an object's intrinsic monitor; it is reentrant and released automatically, even on exceptions.
`ReentrantLock` adds `tryLock` with a timeout, interruptible locking, fairness and multiple `Condition`s, but must be
released in a finally block. `ReadWriteLock` lets many readers proceed together. A deadlock happens when threads hold
locks and wait for each other's locks in a cycle; prevent it by acquiring locks in a consistent global order, by
using `tryLock` with timeouts, or by holding fewer locks. `wait` and `notify` must be called while holding the monitor
and `wait` must be called in a loop because of spurious wake-ups.

## Executors and CompletableFuture

Creating a thread per task is expensive; an `ExecutorService` reuses a pool of threads. A fixed pool bounds
concurrency; an unbounded queue in front of it can grow until memory runs out, so production pools usually use a
bounded queue and a rejection policy. Executors must be shut down. `Future.get` blocks; `CompletableFuture` composes
asynchronous steps with `thenApply`, `thenCompose`, `thenCombine` and `exceptionally`, and by default runs async
stages on the common ForkJoinPool, so blocking I/O should be given its own executor.

## Virtual threads

Virtual threads (Java 21) are lightweight threads scheduled by the JVM onto a small pool of carrier platform threads.
A blocking call unmounts the virtual thread instead of blocking the carrier, so a server can run one virtual thread
per request for millions of concurrent blocking tasks. They help I/O-bound work, not CPU-bound work, should not be
pooled, and can pin their carrier when blocking inside `synchronized` on older JDKs (fixed in JDK 24) or in native
code. Thread-local caches sized for a few platform threads can explode with millions of virtual threads.
