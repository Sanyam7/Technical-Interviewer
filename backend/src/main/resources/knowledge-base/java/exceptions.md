---
title: Java exceptions and error handling
technology: java
skill: java
topic: exceptions
doc_type: REFERENCE
---

## Checked versus unchecked exceptions

`Throwable` has two branches: `Error` for serious problems an application should not catch (such as
`OutOfMemoryError`) and `Exception`. Checked exceptions (subclasses of `Exception` but not of `RuntimeException`)
must be declared or caught, and model recoverable conditions the caller is expected to handle, such as an I/O failure.
Unchecked exceptions (`RuntimeException` and its subclasses) signal programming errors such as
`NullPointerException` or `IllegalArgumentException` and need not be declared. Many modern APIs and frameworks prefer
unchecked exceptions because checked exceptions do not compose well with lambdas and leak implementation details
through method signatures.

## try-with-resources

A try-with-resources statement closes every resource that implements `AutoCloseable`, in reverse order of
declaration, whether the block completes normally or throws. If both the block and `close` throw, the exception from
the block is propagated and the one from `close` is attached as a suppressed exception (`getSuppressed`), instead of
being lost as with a manual finally block.

## finally semantics

A finally block runs after try and catch, even when they return or throw. A return statement in finally overrides
the value or exception of the try block and silently discards an exception, so returning from finally is a bug.
finally does not run if the JVM exits (for example `System.exit`) or the thread is killed.

## Designing exceptions

Throw exceptions at the level of abstraction of the caller and wrap lower-level causes (`new ServiceException("...",
cause)`) so the stack trace keeps the root cause. Never swallow an exception with an empty catch block; at least log
it with context. Catch the most specific type you can handle, and do not use exceptions for normal control flow
because creating a stack trace is costly. Validate arguments early and fail fast with `IllegalArgumentException` or
`Objects.requireNonNull`.
