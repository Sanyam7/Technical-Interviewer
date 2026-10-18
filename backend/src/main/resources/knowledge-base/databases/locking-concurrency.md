---
title: Locking and concurrency control in databases
technology: databases
skill: postgresql
topic: locking-concurrency
doc_type: REFERENCE
---

## Row locks and SELECT FOR UPDATE

UPDATE and DELETE lock the rows they change until the transaction ends; a second writer to the same row waits.
`SELECT ... FOR UPDATE` locks the selected rows for a later update, serialising read-modify-write sequences.
`FOR SHARE` lets others read-lock but not modify. `NOWAIT` fails immediately instead of waiting, and `SKIP LOCKED`
skips rows another transaction holds, which makes a table usable as a work queue where several workers each claim
different rows.

## Optimistic versus pessimistic locking

Pessimistic locking takes locks before working, which is safe under heavy contention but holds locks and connections
while waiting. Optimistic locking reads without locks and checks at write time that nobody changed the row, usually
with a version column (`UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?`); zero updated rows means a
conflict that the application reports or retries. Optimistic locking suits low contention and long user think time.

## Deadlocks

A deadlock is a cycle of transactions each waiting for a lock the next one holds. PostgreSQL detects it after
`deadlock_timeout` and aborts one transaction with an error, which the application should retry. Prevent deadlocks by
locking rows in a consistent order (for example by primary key), keeping transactions short and avoiding user
interaction inside a transaction.

## Table locks and advisory locks

DDL such as `ALTER TABLE` takes strong locks that block reads and writes, and a waiting DDL statement also blocks every
query queued behind it, so migrations on busy tables use `lock_timeout` and online patterns such as
`CREATE INDEX CONCURRENTLY`. Advisory locks are application-defined locks on a number
(`pg_advisory_xact_lock`) used to serialise work such as a scheduled job across several application instances.
